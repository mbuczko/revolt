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

(def ^:const capsule-group         "co/paralleluniverse")
(def ^:const capsule-default-name  "Capsule")
(def ^:const capsule-default-class "Capsule.class")
(def ^:const capsule-maven-class   "MavenCapsule.class")

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

(def system-classpaths
  (memoize (fn []
             (map (memfn getFile)
                  (.getURLs (URLClassLoader/getSystemClassLoader))))))

(defn config->manifest
  "Converts task parameters to capsule manifest tuples."
  [{:keys [caplets scripts] :as config}]
  (-> (reduce-config config)
      (cond-> caplets
        (reduce-caplets caplets))
      (cond-> scripts
        (reduce-scripts scripts))))

(defn root-path->entries
  [root]
  (let [root-path (.toPath root)]
    (->> (file-seq root)
         (map io/file)
         (filter (memfn isFile))
         (map (juxt identity #(-> root-path
                                  (.relativize (.toPath %))
                                  (.toString)))))))

(defn add-to-jar
  [name ^InputStream input-stream ^JarOutputStream jar-stream]
  (log/debug "adding to jar:" name)
  (.putNextEntry jar-stream (ZipEntry. name))
  (io/copy input-stream jar-stream)
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

(defn add-classes
  [^JarOutputStream jar-stream artifact class-p]
  (when-let [capsule-jar (first (filter #(.contains % artifact) (system-classpaths)))]
    (with-open [capsule-is  (io/input-stream capsule-jar)
                capsule-jis (JarInputStream. capsule-is)]
      (loop [^JarEntry entry (.getNextJarEntry capsule-jis)]
        (if-not entry
          jar-stream
          (let [entry-name (.getName entry)]
            (when (class-p entry)
              (add-to-jar entry-name capsule-jis jar-stream))
            (recur (.getNextJarEntry capsule-jis))))))))

(defn add-capsule-class
  [^JarOutputStream jar-stream]
  (add-classes jar-stream
               (str capsule-group "/capsule/")
               (fn [^JarEntry entry]
                 (= (.getName entry) capsule-default-class))))

(defn add-maven-caplet-classes
  [^JarOutputStream jar-stream]
  (add-classes jar-stream
               (str capsule-group "/capsule-maven/")
               (fn [^JarEntry entry]
                 (let [entry-name (.getName entry)]
                   (or (.startsWith entry-name "capsule")
                       (= entry-name capsule-maven-class))))))

(defn add-caplets-jars
  [^JarOutputStream jar-stream caplets]
  (doseq [caplet (filter #(not= "MavenCapsule" %) caplets)]
    (let [[group artifact-id version] (.split caplet ":")
          artifact (str (str/replace group #"\." "/") "/" artifact-id "/" version)]
      (when-let [caplet-jar (->> (system-classpaths)
                                 (filter #(.contains % artifact))
                                 (first)
                                 (io/file))]
        (with-open [is (io/input-stream caplet-jar)]
          (add-to-jar (.getName caplet-jar) is jar-stream)))))
  jar-stream)

(defn add-dependencies
  [^JarOutputStream jar-stream classpaths]
  (doseq [dep (->> classpaths
                   (map io/file)
                   (filter #(and (.isFile %)
                                 (.endsWith (.getName %) ".jar"))))]
    (with-open [is (io/input-stream dep)]
      (add-to-jar (.getName dep) is jar-stream)))
  jar-stream)

(defn add-folders
  [^JarOutputStream jar-stream classpaths]
  (let [file-set (->> classpaths
                      (map io/file)
                      (filter (memfn isDirectory))
                      (mapcat root-path->entries))]
    (doseq [[file name] file-set]
      (with-open [is (io/input-stream file)]
        (add-to-jar name is jar-stream))))
  jar-stream)

(defn build-jar
  "Builds a jar which is essentially a capsule of preconfigured type."
  [^JarOutputStream jar-stream classpath manifest caplets]
  (let [classpaths (str/split classpath (re-pattern File/pathSeparator))]
    (-> jar-stream
        (add-manifest manifest)
        (add-dependencies classpaths)
        (add-folders classpaths)
        (add-capsule-class)
        (add-caplets-jars caplets)
        (cond-> (contains? caplets "MavenCapsule")
            (add-maven-caplet-classes)))))


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
                   {:extra-paths (conj [(utils/ensure-absolute-path target)] extra-paths)})
        jar-file  (io/file output-jar)]

    ;; ensure that all directories in a path are created
    (io/make-parents jar-file)

    (with-open [jar-stream (JarOutputStream. (io/output-stream jar-file))]
      (try
        (build-jar jar-stream
                   classpath
                   (config->manifest input)
                   (into #{} (keys (:caplets input))))

        (catch Throwable t
          (log/errorf "Error while creating a jar file: %s" (.getMessage t))))

    ;; return capsule location as a result
    {:capsule output-jar})))
