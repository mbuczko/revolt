(ns revolt.tasks.capsule
  (:require [clojure.tools.deps.alpha :as tools.deps]
            [clojure.tools.deps.alpha.reader :as tools.deps.reader]
            [clojure.tools.deps.alpha.script.make-classpath]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.utils :as utils])
  (:import  (java.io File InputStream ByteArrayOutputStream)
            (java.net URLClassLoader)
            (java.util.jar Attributes$Name JarEntry JarOutputStream JarInputStream Manifest JarFile)
            (java.util.zip ZipEntry)))

(def ^:const capsule-group "co.paralleluniverse")

(def ^:const capsule-default-name "Capsule")

(def ^:const capsule-default-class (str capsule-default-name ".class"))

(def ^:const capsule-params {:min-java-version "Min-Java-Version"
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

(defn- resolve-sibling-paths
  [paths root]
  (map #(.resolveSibling root %) paths))

(defn config->manifest
  "Converts task parameters to capsule manifest tuples."
  [{:keys [caplets scripts] :as config}]
  (-> (reduce-config config)
      (cond-> caplets
        (reduce-caplets caplets))
      (cond-> scripts
        (reduce-scripts scripts))))

(defn add-to-jar
  [name ^InputStream input-stream ^JarOutputStream jar-stream]
  (log/debug "adding to jar:" name)
  (with-open [input input-stream]
    (.putNextEntry jar-stream (ZipEntry. name))
    (io/copy input jar-stream))
  (doto jar-stream (.closeEntry)))

(defn add-manifest
  [^JarOutputStream jar-stream manifest-tuples]
  (let [manifest (Manifest.)
        attributes (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (.put attributes Attributes$Name/MAIN_CLASS capsule-default-name)
    (.put attributes (Attributes$Name. "Premain-Class") capsule-default-name)
    (doseq [[k v] manifest-tuples]
      (.put attributes (Attributes$Name. k) v))
    (let [baos (ByteArrayOutputStream.)]
      (.write manifest baos)
      (add-to-jar JarFile/MANIFEST_NAME (io/input-stream (.toByteArray baos)) jar-stream))))

(defn add-capsule-class
  [^JarOutputStream jar-stream]
  (let [artifact (str capsule-group "/capsule/")
        classpaths (map (memfn getFile)
                        (.getURLs (URLClassLoader/getSystemClassLoader)))]


    (when-let [capsule-jar (first (filter #(.contains % artifact) classpaths))]
      (let [capsule-jis (JarInputStream. (io/input-stream (File. capsule-jar)))]



        (loop [entry (.getNextJarEntry capsule-jis)]
          (if (= (.getName entry capsule-default-class))
            (add-to-jar capsule-default-class (io/input-stream capsule-jis jar-stream))
            (recur (.getNextJarEntry capsule-jis)))))

      jar-stream)))

(defn build-jar
  "Builds a jar which is essentially a capsule of preconfigured type."
  [^JarOutputStream jar-stream classpath manifest]
  (-> jar-stream
      (add-manifest manifest)
      (add-capsule-class)
      ;add-caplet-classes
      ;add-paths
      ;add-dependencies
      ))


(defn invoke
  [{:keys [exclude-paths extra-paths output-jar] :as input} ctx target]
  (let [deps-edn  (io/file "deps.edn")
        deps-map  (-> deps-edn
                      (tools.deps.reader/slurp-deps)
                      (update :paths filter-paths exclude-paths)
                      (assoc  :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                          "clojars" {:url "https://repo.clojars.org/"}}))
        deps-path (.. deps-edn toPath toAbsolutePath)
        classpath (tools.deps/make-classpath
                   (tools.deps/resolve-deps deps-map nil)
                   (resolve-sibling-paths (:paths deps-map) deps-path)
                   {:extra-paths (conj [target] extra-paths)})
        jar-file  (io/file output-jar)]

    ;; ensure that all directories in a path are created
    (io/make-parents jar-file)

    (with-open [s (JarOutputStream. (io/output-stream jar-file))]
      (try
        (build-jar s classpath (config->manifest input))
        (catch Throwable t
          (log/errorf "Error while creating a jar file: %s" (.getMessage t))))

    ;; return capsule location as a result
    {:capsule output-jar})))
