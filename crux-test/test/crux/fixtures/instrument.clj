(ns crux.fixtures.instrument
  (:require [crux.db :as db]
            [crux.index :as i]
            [crux.memory :as mem]
            [clojure.tools.logging :as log]))

(defmulti index-name (fn [i] (-> i ^java.lang.Class type .getName symbol)))

(defmethod index-name :default [i]
  (-> i ^java.lang.Class type .getName (clojure.string/replace #"crux\.index\." "") symbol))

(defmethod index-name 'crux.index.NAryConstrainingLayeredVirtualIndex [i]
  (str "NAry (Constrained): " (clojure.string/join " " (map :name (:indexes i)))))

(defmethod index-name 'crux.index.NAryJoinLayeredVirtualIndex [i]
  (str "NAry: " (clojure.string/join " " (map :name (:indexes i)))))

(defmethod index-name 'crux.index.UnaryJoinVirtualIndex [i]
  (str "Unary: " (clojure.string/join " " (map :name (:indexes i)))))

(defmethod index-name 'crux.index.BinaryJoinLayeredVirtualIndex [i]
  (format "Binary: [%s %s %s]" (-> i meta :clause :e) (-> i meta :clause :a) (-> i meta :clause :v)))

(defmethod index-name 'crux.index.DocAttributeValueEntityEntityIndex [i]
  "AVE-E:")

(defmethod index-name 'crux.index.DocAttributeValueEntityValueIndex [i]
  "AVE-V:")

(defmethod index-name 'crux.index.DocAttributeEntityValueEntityIndex [i]
  "AVE-E:")

(defmethod index-name 'crux.index.DocAttributeEntityValueValueIndex [i]
  "AEV-V:")

(defn- trunc
  [s n]
  (subs s 0 (min (count s) n)))

(defn- trace-op [foo op depth & extra]
  (print (format "%s %s%s %s" (name op) @foo (apply str (take (get @depth op) (repeat " ")))
                   (clojure.string/join " " extra))))

(defn- v->str [v]
  (str "["(trunc (str (mem/buffer->hex (first v))) 10) " " (trunc (str (second v)) 40) "]"))

(defmulti index-seek (fn [i depth foo k] (-> i ^java.lang.Class type .getName symbol)))

(defmethod index-seek :default [i depth foo k]
  (trace-op foo :seek depth (index-name i))
  (if (#{'crux.index.DocAttributeValueEntityEntityIndex
         'crux.index.DocAttributeValueEntityValueIndex
         'crux.index.DocAttributeEntityValueEntityIndex
         'crux.index.DocAttributeEntityValueValueIndex}
       (-> i ^java.lang.Class type .getName symbol))
    (do
      (swap! depth update :seek inc)
      (let [v (db/seek-values i k)]
        (println (v->str v))
        (swap! depth update :seek dec)
        v))
    (do
      (println)
      (swap! depth update :seek inc)
      (let [v (db/seek-values i k)]
        (trace-op foo :seek depth "--->" (v->str v))
        (println)
        (swap! depth update :seek dec)
        v))))

(defrecord InstrumentedLayeredIndex [i depth foo]
  db/Index
  (seek-values [this k]
    (index-seek i depth foo k))

  (next-values [this]
    (trace-op foo :next depth (index-name i))
    (println)
    (swap! depth update :next inc)
    (let [v (db/next-values i)]
      (swap! depth update :next dec)
      v))

  db/LayeredIndex
  (open-level [this]
    (swap! foo inc)
    (db/open-level i))

  (close-level [this]
    (db/close-level i)
    (swap! foo dec))

  (max-depth [this]
    (db/max-depth i)))

(defprotocol Instrument
  (instrument [i depth visited]))

(defn inst [depth visited i]
  (instrument i depth visited))

(defn ->instrumented-index [i depth visited]
  (or (and (instance? InstrumentedLayeredIndex i) i)
      (get @visited i)
      (let [ii (InstrumentedLayeredIndex. i depth (atom 0))]
        (swap! visited assoc i ii)
        ii)))

(extend-protocol Instrument
  crux.index.NAryConstrainingLayeredVirtualIndex
  (instrument [this depth visited]
    (let [this (update this :n-ary-index (partial inst depth visited))]
      (->instrumented-index this depth visited)))

  crux.index.NAryJoinLayeredVirtualIndex
  (instrument [this depth visited]
    (let [this (update this :unary-join-indexes (fn [indexes] (map (partial inst depth visited) indexes)))]
      (->instrumented-index this depth visited)))

  crux.index.UnaryJoinVirtualIndex
  (instrument [this depth visited]
    (let [this (update this :indexes (fn [indexes] (map (partial inst depth visited) indexes)))]
      (->instrumented-index this depth visited)))

  crux.index.BinaryJoinLayeredVirtualIndex
  (instrument [^crux.index.BinaryJoinLayeredVirtualIndex this depth visited]
    (let [state ^crux.index.BinaryJoinLayeredVirtualIndexState (.state this)
          [lhs rhs] (map (partial inst depth visited) (.indexes state))]
      (set! (.indexes state) [lhs rhs])
      (merge (->instrumented-index this depth visited) this)))

  crux.index.RelationVirtualIndex
  (instrument [^crux.index.RelationVirtualIndex this depth visited]
    (let [state ^crux.index.RelationIteratorsState (.state this)]
      (set! (.indexes state) (mapv (partial inst depth visited) (.indexes state)))
      (->instrumented-index this depth visited)))

  Object
  (instrument [this depth visited]
    (->instrumented-index this depth visited)))

(def original-layered-idx->seq i/layered-idx->seq)
(defn instrument-layered-idx->seq [idx]
  (original-layered-idx->seq (instrument idx (atom {:seek 0 :next 0}) (atom {}))))

(defmacro with-instrumentation [& form]
  `(with-redefs [i/layered-idx->seq instrument-layered-idx->seq]
     ~@form))
