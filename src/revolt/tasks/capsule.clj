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

(defn invoke
  [{:keys [exclude-paths
           extra-paths
           output-jar
           manifest
           group
           version]} target]

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
     
     ;; map of manifest key-values
     (merge {"Application-ID" group
             "Application-Version" version}
            manifest))))
