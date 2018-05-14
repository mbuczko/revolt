(ns revolt.tasks.capsule
  (:require [clojure.tools.deps.alpha :as tools.deps]
            [clojure.tools.deps.alpha.reader :as tools.deps.reader]
            [clojure.tools.deps.alpha.script.make-classpath]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.assembly :refer [spit-jar!]]
            [revolt.utils :as utils]
            [revolt.elodin :as elodin])
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

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn classpath->jar
  [classpath]
  (let [cp (map io/file (str/split classpath (re-pattern File/pathSeparator)))]
    (spit-jar!
     "dist"
     (concat

      ;; directories on the classpath
      (mapcat
       (fn [dir]
         (let [path (.toPath dir)]
           (map
            (juxt #(str (.relativize path (.toPath %))) io/file)
            (filter (memfn isFile) (file-seq dir)))))
       (filter (memfn isDirectory) cp))

      ;; jar deps
      (sequence
       (comp
        (map file-seq)
        cat
        (filter (memfn isFile))
        (filter #(by-ext % "jar"))
        (map (juxt (comp utils/ensure-relative-path
                         elodin/hash-derived-name)
                   io/file)))
       cp)      
      [["Capsule.class" (io/resource "Capsule.class")]])
     (cond->
         [["Application-Class" "clojure.main"]
          ["Application-ID" application-id]
          ["Application-Version" application-version]]
       main
       (conj ["Args" (str "-m " main)]))
     
     "Capsule")))

(defn invoke
  [{:keys [exclude-paths extra-paths]} target]

  (let [deps-edn  (io/file "deps.edn")
        deps-map  (-> deps-edn
                      (tools.deps.reader/slurp-deps)
                      (update :paths filter-paths exclude-paths)
                      (assoc  :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                          "clojars" {:url "https://repo.clojars.org/"}}))
        deps-path (.. deps-edn toPath toAbsolutePath)]

    (classpath->jar
     (tools.deps/make-classpath
      (tools.deps/resolve-deps deps-map nil)
      (resolve-sibling-paths (:paths deps-map) deps-path)
      {:extra-paths extra-paths}))

    ))
