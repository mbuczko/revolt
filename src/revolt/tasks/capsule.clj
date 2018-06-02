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
  [initial config]
  (reduce (fn [parameters k]
            (if-let [param (k capsule-params)]
              (conj parameters [param (k config)])
              parameters))
          initial
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

  [{:keys [caplets scripts main group args] :as config}]
  (-> [["Application-Class" "clojure.main"]
       ["Premain-Class" capsule-default-name]
       ["Args" (str "-m " main " " args)]]
      (reduce-config config)
      (cond-> caplets
        (reduce-caplets caplets))
      (cond-> scripts
        (reduce-scripts scripts))))

(defn dir->relative-entries
  "Returns a collection of [file relative-path] tuples for each file
  inside provided directory and all nested subdirectories.

  `relative-path` is relative to dir itself."

  [dir]
  (let [root-path  (.toPath dir)]
    (->> (file-seq dir)
         (map io/file)
         (filter (memfn isFile))
         (map (juxt identity #(-> root-path
                                  (.relativize (.toPath %))
                                  (.toString)))))))

(defn create-jar-entry
  "Creates a new entry in a jar out of given name and InputStream."

  [name ^InputStream input-stream ^JarOutputStream jar-stream]
  (log/debug "adding to jar:" name)
  (.putNextEntry jar-stream (ZipEntry. name))
    (io/copy input-stream jar-stream)
  (doto jar-stream (.closeEntry)))

(defn add-to-jar
  "Adds new entry to jar file.

  Acts as a safe proxy to `create-jar-entry` by creating and safely
  closing entry InputStream."

  [name input ^JarOutputStream jar-stream]
  (with-open [input-stream (io/input-stream input)]
    (create-jar-entry name input-stream jar-stream)))

(defn add-manifest
  "Adds a manifest entry to the jar file."

  [^JarOutputStream jar-stream manifest-tuples]
  (let [manifest (Manifest.)
        attributes (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (.put attributes Attributes$Name/MAIN_CLASS capsule-default-name)
    (doseq [[k v] manifest-tuples]
      (.put attributes (Attributes$Name. k) v))
    (let [baos (ByteArrayOutputStream.)]
      (.write manifest baos)
      (add-to-jar JarFile/MANIFEST_NAME (.toByteArray baos) jar-stream))))

(defn add-classes-from-artifact
  [^JarOutputStream jar-stream artifact class-p]
  (when-let [capsule-jar (first (filter #(.contains % artifact) (system-classpaths)))]
    (with-open [capsule-is  (io/input-stream capsule-jar)
                capsule-jis (JarInputStream. capsule-is)]
      (loop [^JarEntry entry (.getNextJarEntry capsule-jis)]
        (if-not entry
          jar-stream
          (let [entry-name (.getName entry)]
            (when (class-p entry)
              (create-jar-entry entry-name capsule-jis jar-stream))
            (recur (.getNextJarEntry capsule-jis))))))))

(defn add-capsule-class
  [^JarOutputStream jar-stream]
  (add-classes-from-artifact jar-stream
                             (str capsule-group "/capsule/")
                             (fn [^JarEntry entry]
                               (= (.getName entry) capsule-default-class))))

(defn add-maven-caplet-classes
  [^JarOutputStream jar-stream]
  (add-classes-from-artifact jar-stream
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
        (add-to-jar (.getName caplet-jar) caplet-jar jar-stream))))
  jar-stream)

(defn add-dependencies
  [^JarOutputStream jar-stream classpaths]
  (doseq [dep (->> classpaths
                   (map io/file)
                   (filter #(and (.isFile %)
                                 (.endsWith (.getName %) ".jar"))))]
    (add-to-jar (.getName dep) dep jar-stream))
  jar-stream)

(defn add-paths
  [^JarOutputStream jar-stream classpaths aot?]
  (doseq [[file name] (->> classpaths
                           (map io/file)
                           (filter (memfn isDirectory))
                           (mapcat dir->relative-entries))
          :when (not (and aot? (.endsWith name ".clj")))]
    (add-to-jar name file jar-stream))
  jar-stream)

(defn ensure-runtime-deps
  [manifest deps capsule-type]
  (let [artifacts (->> deps
                       (map (fn [[dep coords]]
                              (when-let [version (:mvn/version coords)]
                                (str (str/replace dep "/" ":") ":" version))))
                       (filter (complement nil?))
                       (into #{}))]

    (if (not= (count artifacts)
              (count deps))
      (throw (Exception. "Some of dependecies have no :mvn/version assigned!"))
      (condp = capsule-type
        :thin
        (-> manifest
            (utils/assoc-tuple-merging "Caplets" #{"MavenCapsule"})
            (utils/assoc-tuple-merging "Repositories" #{"central" "clojars(https://repo.clojars.org/)"})
            (utils/assoc-tuple-merging "Dependencies" artifacts))

        ;; return untouched manifest by default
        manifest))))


(defn build-jar
  "Builds a jar which is essentially a capsule of preconfigured type."

  [^JarOutputStream jar-stream classpath deps manifest caplets capsule-type aot?]
  (let [classpaths (str/split classpath (re-pattern File/pathSeparator))]
    (-> jar-stream
        (add-manifest (ensure-runtime-deps manifest deps capsule-type))
        (add-capsule-class)
        (add-caplets-jars caplets)

        ;; :empty capsule has no dependecies at all (even app-related)
        ;; :thin capsule has only application dependecies included.

        (cond-> (not= capsule-type :empty)
          (add-paths classpaths aot?))

        ;; :fat capsule has all dependecies included.
        ;; it's simply self-contained uberjar.

        (cond-> (= capsule-type :fat)
          (add-dependencies classpaths))

        ;; maven capsule should be added either when declared directly
        ;; or when capsule type is :empty or :thin which means that
        ;; dependencies should be pulled in a runtime.

        (cond-> (or (contains? caplets "MavenCapsule")
                    (not= capsule-type :fat))
          (add-maven-caplet-classes)))))


(defn invoke
  [{:keys [exclude-paths extra-paths capsule-type output-jar] :as input} ctx target]
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
                   {:extra-paths (->> [target extra-paths]
                                      (map utils/ensure-absolute-path)
                                      (filter (complement nil?)))})
        jar-file  (io/file output-jar)]

    ;; ensure that all directories in a path are created
    (io/make-parents jar-file)

    (utils/timed "CAPSULE"
     (with-open [jar-stream (JarOutputStream. (io/output-stream jar-file))]
       (try
         (build-jar jar-stream
                    classpath
                    (:deps deps-map)
                    (config->manifest input)
                    (into #{} (keys (:caplets input)))
                    :thin
                    (:aot? ctx))

         (catch Throwable t
           (log/errorf "Error while creating a jar file: %s" (.getMessage t))))

       ;; return capsule location as a result
       {:uberjar output-jar}))))
