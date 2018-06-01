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
  (if (second (and s (.split s "/")))
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
  (when-let [root-dir (.toString (current-dir))]
    (.toString (Paths/get root-dir (into-array [relative-path])))))

(defn build-params-list
  [input-params kw]
  (->> (str/split (kw input-params) #",")
       (map (partial ensure-ns "revolt.plugin"))))

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
