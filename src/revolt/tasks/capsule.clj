(ns revolt.tasks.capsule
  (:require [clojure.tools.deps.alpha :as tools.deps]
            [clojure.tools.deps.alpha.reader :as tools.deps.reader]
            [clojure.tools.deps.alpha.script.make-classpath]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.utils :as utils]
            [mach.pack.alpha.capsule :as capsule])
  (:import  (java.io File)))

(defn filter-paths
  [paths to-exclude]
  (cond
    (set? to-exclude)
    (filterv #(not (contains? to-exclude %)) paths)

    (= (type to-exclude) java.util.regex.Pattern)
    (filterv #(not (re-matches to-exclude %)) paths)

    :else
    paths))

(defn resolve-sibling-paths
  [paths root]
  (map #(.resolveSibling root %) paths))

(defn add-tuple-maybe
  "Adds a new tuple [k v] only if there was no k already among provided tuples.
  Newly added tuple is placed at the end of vector of tuples."
  [tuples k v]
  (if (seq (filterv #(= (first %) k) tuples))
    tuples
    (conj tuples [k v])))

(defn invoke
  [{:keys [exclude-paths extra-paths output-jar manifest]} ctx target]
  (let [deps-edn  (io/file "deps.edn")
        deps-map  (-> deps-edn
                      (tools.deps.reader/slurp-deps)
                      (update :paths filter-paths exclude-paths)
                      (assoc  :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                          "clojars" {:url "https://repo.clojars.org/"}}))
        deps-path (.. deps-edn toPath toAbsolutePath)]

    (capsule/classpath-string->jar
     (tools.deps/make-classpath
      (tools.deps/resolve-deps deps-map nil)
      (resolve-sibling-paths (:paths deps-map) deps-path)
      {:extra-paths (conj ["target"] extra-paths)})

     ;; resulting capsule jar location
     output-jar

     ;; generate vector of manifest tuples
     (-> manifest
         (add-tuple-maybe "Application-ID" (:group ctx))
         (add-tuple-maybe "Application-Version" (:version ctx))))

    ;; return capsule location as a result
    {:capsule output-jar}))
