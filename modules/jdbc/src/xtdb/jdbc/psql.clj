(ns ^:no-doc xtdb.jdbc.psql
  (:require [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.nippy.v3v1v1.taoensso.nippy :as nippy]
            [xtdb.jdbc :as j]
            [xtdb.system :as sys]
            [juxt.clojars-mirrors.nextjdbc.v1v2v674.next.jdbc :as jdbc]
            [juxt.clojars-mirrors.nextjdbc.v1v2v674.next.jdbc.result-set :as jdbcr]))

(defn- check-tx-time-col [pool]
  (when-not (= "timestamp with time zone"
               (-> (jdbc/execute-one! pool
                                      ["SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'tx_events' AND COLUMN_NAME = 'tx_time'"]
                                      {:builder-fn jdbcr/as-unqualified-lower-maps})
                   :data_type))
    (log/warn (str "`tx_time` column not in UTC format. "
                   "See https://github.com/xtdb/xtdb/releases/tag/20.09-1.12.1 for more details."))))

(defn ->dialect {::sys/args {:drop-table? {:spec ::sys/boolean, :default false}}}
  [{:keys [drop-table?]}]
  (reify j/Dialect
    (db-type [_] :postgresql)

    (setup-schema! [_ pool]
      (jdbc/with-transaction [tx pool]
        (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(-3455654)"])

        (when drop-table?
          (jdbc/execute! tx ["DROP TABLE IF EXISTS tx_events"]))


        (doto tx
          (jdbc/execute! ["
CREATE TABLE IF NOT EXISTS tx_events (
  event_offset BIGSERIAL PRIMARY KEY,
  event_key VARCHAR,
  tx_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  topic VARCHAR NOT NULL,
  v BYTEA NOT NULL,
  compacted INTEGER NOT NULL)"])

          (jdbc/execute! ["DROP INDEX IF EXISTS tx_events_event_key_idx"])
          (jdbc/execute! ["CREATE INDEX IF NOT EXISTS tx_events_event_key_idx_2 ON tx_events(event_key)"])
          (check-tx-time-col))))

    (ensure-serializable-identity-seq! [_ tx table-name]
      ;; we have to take a table write lock in Postgres, because auto-increments aren't guaranteed to be increasing, even between transactions with 'serializable' isolation level
      ;; `table-name` is trusted
      (jdbc/execute! tx [(format "LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE" table-name)]))

    j/DialectInsertEvent
    (insert-event!* [_ pool event-key v topic]
      (let [b (nippy/freeze v)
            ;; see #1918
            sql (if (= "txs" topic)
                  "INSERT INTO tx_events (EVENT_KEY, TX_TIME, V, TOPIC, COMPACTED)
                   VALUES (?, statement_timestamp(), ?,?,0)"
                  "INSERT INTO tx_events (EVENT_KEY, V, TOPIC, COMPACTED)
                   VALUES (?, ?,?,0)")]
        (jdbc/execute-one! pool [sql event-key b topic] {:return-keys true :builder-fn jdbcr/as-unqualified-lower-maps})))))
