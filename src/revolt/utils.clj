(ns revolt.utils
  (:require [clojure.edn :as edn]
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

(defn read-project-info
  [target]
  (safe-read-edn
   (ensure-relative-path target "project.edn")))

(defmacro timed
  "Evaluates expr and prints the time it took.  Returns the value of expr."
  [expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (println (format "Elapsed time: %.2f secs" (/ (double (- (. System (nanoTime)) start#)) 1000000000.0)))
     ret#))
