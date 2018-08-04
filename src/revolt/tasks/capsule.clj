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
                             :native-dependencies "Native-Dependencies"
                             :capsule-log-level "Capsule-Log-Level"})

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

(defn config->application-manifest
  "Converts task parameters to capsule manifest tuples."

  [{:keys [caplets scripts main args] :as config} application version]
  (-> [["Application-Class" "clojure.main"]
       ["Application-Version" version]
       ["Application-ID" (str/replace application ":" ".")]
       ["Premain-Class" capsule-default-name]
       ["Args" (str "-m " main " " args)]]

      (reduce-config config)
      (cond-> caplets
        (reduce-caplets caplets))
      (cond-> scripts
        (reduce-scripts scripts))))

(defn dir->relative-entries
  "Returns a collection of [file relative-path] tuples for each file
  inside directory and all its nested subdirectories.

  `relative-path` is relative to dir itself."

  [dir]
  (when-let [root-path (and (not= (.getName dir) "capsule") (.toPath dir))]
    (->> (file-seq dir)
         (map io/file)
         (filter (memfn isFile))
         (filter #(not= (.getName %) ".DS_Store"))
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
  (if-let [capsule-jar (first (filter #(.contains % artifact) (system-classpaths)))]
    (with-open [capsule-is  (io/input-stream capsule-jar)
                capsule-jis (JarInputStream. capsule-is)]
      (loop [^JarEntry entry (.getNextJarEntry capsule-jis)]
        (if-not entry
          jar-stream
          (let [entry-name (.getName entry)]
            (when (class-p entry)
              (create-jar-entry entry-name capsule-jis jar-stream))
            (recur (.getNextJarEntry capsule-jis))))))
    (log/error "No capsule jar found. Check for co.paralleluniverse/capsule in dependencies.")))

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
          clpath (str (str/replace group #"\." "/") "/" artifact-id "/" version)]
      (when-let [caplet-jar (->> (system-classpaths)
                                 (filter #(.contains % clpath))
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
  [^JarOutputStream jar-stream classpaths aot? morph-fn]
  (try
    (doseq [[file name] (->> classpaths
                             (map io/file)
                             (filter (memfn isDirectory))
                             (mapcat dir->relative-entries))
            :when (not (and aot? (.endsWith name ".clj")))]

      ;; morphing function may return nil which means file should
      ;; not be included into resulting uberjar.
      ;; otherwise returned (possibly altered) file added.

      (when-let [resource (if morph-fn (morph-fn file name) file)]
        (add-to-jar name resource jar-stream)))

    (catch Exception e
      (log/error (.getMessage e))))
  jar-stream)

(defn ensure-runtime-deps
  [application manifest deps capsule-type]
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
        :empty
        (-> manifest
            (utils/assoc-tuple-merging "Application" #{application}))

        :thin
        (-> manifest
            (utils/assoc-tuple-merging "Caplets" #{"MavenCapsule"})
            (utils/assoc-tuple-merging "Repositories" #{"central" "clojars(https://repo.clojars.org/)"})
            (utils/assoc-tuple-merging "Dependencies" artifacts))

        ;; return untouched manifest by default
        manifest))))

(defn build-jar
  "Builds a jar which is essentially a capsule of preconfigured type."

  [^JarOutputStream jar-stream application classpath manifest deps caplets capsule-type {:keys [aot? morph-fn]}]
  (let [classpaths (str/split classpath (re-pattern File/pathSeparator))]
    (-> jar-stream
        (add-manifest (ensure-runtime-deps application manifest deps capsule-type))
        (add-capsule-class)
        (add-caplets-jars caplets)

        ;; :empty capsule has no dependecies at all (even app-related)
        ;; :thin capsule has only application dependecies included.

        (cond-> (not= capsule-type :empty)
          (add-paths classpaths aot? morph-fn))

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
  [ctx {:keys [exclude-paths extra-paths capsule-type output-jar name package version] :as config} target]

  (let [artifact-id (:name ctx name)
        group-id    (:package ctx package)
        version     (:version ctx version)
        caps-type   (or capsule-type :fat)]

    (if-let [application (and artifact-id group-id (str group-id ":" artifact-id))]
      (utils/timed
       (str "CAPSULE " application ":" (or version "<missing version>") " (" caps-type ")")
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
                        {:extra-paths (->> ["classes" "assets"]
                                           (map (partial utils/ensure-relative-path target))
                                           (concat extra-paths)
                                           (map utils/ensure-absolute-path)
                                           (filter (complement nil?)))})
             jar-file  (io/file output-jar)]

         ;; ensure that all directories in a path are created
         (io/make-parents jar-file)

         (with-open [jar-stream (JarOutputStream. (io/output-stream jar-file))]
           (try
             (build-jar jar-stream
                        application
                        classpath
                        (config->application-manifest config application version)
                        (:deps deps-map)
                        (into #{} (keys (:caplets config)))
                        (keyword caps-type)
                        ctx)

             (catch Throwable t
               (log/errorf "Error while creating a jar file: %s" (.getMessage t))))

           ;; return capsule location as a result
           (assoc ctx :uberjar output-jar))))

      (throw (Exception. "No 'name' or 'package' parameters provided in task configuration and context.")))))
