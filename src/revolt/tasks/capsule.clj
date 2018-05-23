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

(def capsule-params {:min-java-version "Min-Java-Version"
                     :min-update-version "Min-Update-Version"
                     :java-version "Java-Version"
                     :jdk-required? "JDK-Required"
                     :jvm-args "JVM-Args"
                     :args "Args"
                     :main "Application-Class"
                     :environment-variables "Environment-Variables"
                     :system-properties "System-Properties"
                     :security-manager "Security-Manager"
                     :security-policy "Security-Policy"
                     :security-policy-appended "Security-Policy-A"
                     :java-agents "Java-Agents"
                     :native-agents "Native-Agents"
                     :dependencies "Dependencies"
                     :native-dependencies "Native-Dependencies"
                     :capsule-log-level "Capsule-Log-Level"
                     :version "Application-Version"
                     :group "Application-ID"})

(defn filter-paths
  [paths to-exclude]
  (cond
    (set? to-exclude)
    (filterv #(not (contains? to-exclude %)) paths)

    (= (type to-exclude) java.util.regex.Pattern)
    (filterv #(not (re-matches to-exclude %)) paths)

    :else
    paths))

(defn- reduce-caplets
  [manifest caplets]
  (let [kv (reduce (fn [reduced [caplet opts]]
                     (-> reduced
                         (update :caplets conj caplet)
                         (update :opts concat opts)))
                   {:caplets [] :opts []}
                   caplets)]
    (-> manifest
        (conj ["Caplets" (str/join " " (:caplets kv))])
        (concat (:opts kv)))))

(defn- reduce-scripts
  [manifest scripts]
  (reduce (fn [reduced [name application-script]]
            (concat reduced [["Name" name]
                             ["Application-Script" application-script]]))
          manifest
          scripts))

(defn- reduce-config
  [config]
  (reduce (fn [parameters k]
            (if-let [param (k capsule-params)]
              (conj parameters [param (k config)])
              parameters))
          []
          (keys config)))

(defn config->manifest
  "Converts task parameters to capsule manifest tuples."
  [{:keys [caplets scripts] :as config}]
  (-> (reduce-config config)
      (cond-> caplets
        (reduce-caplets caplets))
      (cond-> scripts
        (reduce-scripts scripts))))

(defn resolve-sibling-paths
  [paths root]
  (map #(.resolveSibling root %) paths))

(defn invoke
  [{:keys [exclude-paths extra-paths output-jar] :as input} ctx target]
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
      {:extra-paths (conj [target] extra-paths)})

     ;; resulting capsule jar location
     output-jar

     ;; generate vector of manifest tuples
     (config->manifest input))

    ;; return capsule location as a result
    (assoc ctx :capsule output-jar)))
