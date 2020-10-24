(ns revolt.utils
  (:require [io.aviso.ansi]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import  (java.nio.file Paths)
            (java.io File)
            (java.security MessageDigest)))

(defn gather-paths
  [paths]
  (into #{}
        (map #(-> (io/file %)
                  .toPath
                  .toAbsolutePath)
             paths)))

(defn filter-paths
  [paths to-exclude]
  {:pre [(set? to-exclude)]}
  (filterv #(not (contains? to-exclude %)) paths))

(defn resolve-sibling-paths
  [paths root]
  (map #(str (.resolveSibling root %)) paths))

(defn current-dir
  ([]
   (current-dir (java.nio.file.FileSystems/getDefault)))
  ([^java.nio.file.FileSystem fs]
   (.toAbsolutePath (.getPath fs "" (make-array String 0)))))

(defn ensure-ns
  "Ensures that a string, to be later transformed to a namespaced keyword,
  contains a namespace part. In case there is no namespace - a default one
  is added."
  [default-ns s]
  {:pre [(string? s)]}
  (if (second (.split s "/"))
    s
    (str default-ns "/" s)))

(defn ensure-relative-path
  ([path]
   (ensure-relative-path "" path))
  ([target path]
   (when path
     (.toString (Paths/get target (.split path File/separator))))))

(defn ensure-absolute-path
  [relative-path]
  (when-let [root-dir (and relative-path (.toString (current-dir)))]
    (.toString (Paths/get root-dir (into-array [relative-path])))))

(defn ensure-relative-outputs
  "Ensures that `:output-to` of each clojurescript module
  is relative to given path."

  [modules-kv path]
  (apply merge
   (for [[id module] modules-kv]
     (hash-map id (update module :output-to (partial ensure-relative-path path))))))

(defn cons-nested
  "Transforms stringified nested key and its value into a proper map,
  eg. \"compiler.optimizations\" key with value \"advanced\" will be
  transformed into a map {:compiler {:optimizations \"advanced\"}}"

  [nk v]
  (let [keys (rseq (str/split nk #"\."))]
    (loop [[k & tail] keys, result nil]
      (if-not k
        result
        (recur tail (hash-map (keyword k) (or result v)))))))

(defn make-options-coll
  "Transforms an equal-sign separated string into a {:option value} map.
  For example: \"type=thin\" will be transformed into {:type \"thin\"}.

  Options can be nested with a dot sign and will be turned into a nested maps,
  so \"capsule.type=thin\" will be transformed into {:capsule {:type \"thin\"}}"

  [options-str]
  (let [opts (map #(str/split % #"=") options-str)]
    (reduce (fn [reduced [nk v]]
              (merge-with merge reduced (cons-nested nk v)))
            {}
            opts)))

(defn make-params-coll
  "Transforms a comma separated string of \"task1:options,task2:options\"
  into a collection of vectors ([task1 options] [task2 options]]).

  Options are colon separated tuples like \"type=thin:name=foo\", can be
  nested with a dot sign, like \"capsule.type=thin\" and get transformed
  into corresponding (possibly nested) maps."

  [params-str default-ns]
  (let [params (and params-str (.split params-str ","))]
    (reduce (fn [reduced param]
              (let [[p & opts] (.split param ":")]
                (conj reduced [(ensure-ns default-ns p)
                               (make-options-coll opts)])))
            []
            params)))

(defn safe-read-edn
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception ex
      (log/debug "No project information found in" path))))

(defn dissoc-maybe
  "Dissocs nested key from a map when given predicate is true."
  [m k pred]
  (if pred
    (if-let [nav (seq (pop k))]
      (update-in m nav dissoc (last k))
      (dissoc m (first k)))
    m))

(defn assoc-tuple-merging
  [tuples k v]
  (if-let [tuple-string (get (into {} tuples) k)]
    (let [tuple-values (into #{} (.split tuple-string " "))]
      (-> (filter #(not= (first %) k) tuples)
          (conj [k (str/join " " (into tuple-values v))])))
    (conj tuples [k (str/join " " v)])))

(defmacro timed
  "Evaluates expr and prints the time it took. Returns the value of expr."
  [task expr]
  `(do
     (println (io.aviso.ansi/green ~task))
     (let [start# (. System (nanoTime))
          ret# ~expr]
      (println (format "=> elapsed time: %.2f secs" (/ (double (- (. System (nanoTime)) start#)) 1000000000.0)))
      ret#)))
