(ns revolt.tasks.capsule
  (:require [clojure.tools.deps.alpha :as tools.deps]
            [clojure.tools.deps.alpha.reader :as tools.deps.reader]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.tasks.jar :as jar]
            [revolt.utils :as utils])
  (:import  (java.io File)
            (java.util.jar Attributes$Name JarEntry JarOutputStream JarFile)))

(def ^:const capsule-group         "co/paralleluniverse")
(def ^:const capsule-default-class "Capsule.class")
(def ^:const capsule-default-name  "Capsule")
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

(defn config->manifest-entries
  "Converts task parameters to capsule manifest entries."

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

(defn add-capsule-class
  [^JarOutputStream jar-stream]
  (jar/add-classes-from-artifact
   jar-stream
   (str capsule-group "/capsule/")
   (fn [^JarEntry entry]
     (= (.getName entry) capsule-default-class))))

(defn add-maven-caplet-classes
  [^JarOutputStream jar-stream]
  (jar/add-classes-from-artifact
   jar-stream
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
      (when-let [caplet-jar (->> (jar/classpaths)
                                 (filter #(.contains % clpath))
                                 (first)
                                 (io/file))]
        (jar/add-to-jar (.getName caplet-jar) caplet-jar jar-stream))))
  jar-stream)

(defn add-dependencies
  [^JarOutputStream jar-stream classpaths]
  (doseq [dep (->> classpaths
                   (map io/file)
                   (filter #(and (.isFile %)
                                 (.endsWith (.getName %) ".jar"))))]
    (jar/add-to-jar (.getName dep) dep jar-stream))
  jar-stream)

(defn ensure-runtime-deps
  [application manifest-entries deps capsule-type]
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
        (-> manifest-entries
            (utils/assoc-tuple-merging "Application" #{application}))

        :thin
        (-> manifest-entries
            (utils/assoc-tuple-merging "Caplets" #{"MavenCapsule"})
            (utils/assoc-tuple-merging "Repositories" #{"central" "clojars(https://repo.clojars.org/)"})
            (utils/assoc-tuple-merging "Dependencies" artifacts))

        ;; return untouched manifest entries by default
        manifest-entries))))

(defn build-jar
  "Builds a jar which is essentially a capsule of preconfigured type."

  [^JarOutputStream jar-stream application classpath caplets capsule-type {:keys [aot? before-pack-fns]}]
  (let [classpaths (str/split classpath (re-pattern File/pathSeparator))]

    (-> jar-stream
        (add-capsule-class)
        (add-caplets-jars caplets)

        ;; :empty capsule has no dependecies at all (even app-related)
        ;; :thin capsule has only application dependecies included.

        (cond-> (not= capsule-type :empty)
          (jar/add-paths classpaths aot? before-pack-fns))

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
  (let [artifact-id (or name (:name ctx))
        group-id    (or package (:package ctx))
        version     (or version (:version ctx))
        caps-type   (or capsule-type :fat)]

    (if-let [application (and artifact-id group-id (str group-id ":" artifact-id))]
      (utils/timed
       (str "CAPSULE " application ":" (or version "<missing version>") " (" caps-type ")")
       (let [deps-edn  (io/file "deps.edn")
             deps-map  (-> deps-edn
                           (tools.deps.reader/slurp-deps)
                           (update :paths utils/filter-paths (set exclude-paths))
                           (update :mvn/repos merge jar/default-mvn-repos))
             deps-path (.. deps-edn toPath toAbsolutePath)
             classpath (tools.deps/make-classpath
                        (tools.deps/resolve-deps deps-map nil)
                        (utils/resolve-sibling-paths (:paths deps-map) deps-path)
                        {:extra-paths (->> ["classes" "assets"]
                                           (map (partial utils/ensure-relative-path target))
                                           (concat extra-paths)
                                           (map utils/ensure-absolute-path)
                                           (filter (complement nil?)))})
             output-jar (or output-jar (jar/default-output-jar artifact-id version))
             jar-file   (io/file output-jar)
             entries    (config->manifest-entries config application version)
             manifest   (jar/create-manifest
                         (ensure-runtime-deps application entries (:deps deps-map) capsule-type) capsule-default-name)]

         ;; ensure that all directories in a path are created
         (io/make-parents jar-file)

         (with-open [jar-stream (JarOutputStream. (io/output-stream jar-file) manifest)]
           (try
             (build-jar jar-stream
                        application
                        classpath
                        (into #{} (keys (:caplets config)))
                        (keyword caps-type)
                        ctx)

             (catch Throwable t
               (log/errorf "Error while creating a capsule file: %s" (.getMessage t))))

           ;; return capsule location as a result
           (assoc ctx :jar-file output-jar))))

      (throw (Exception. "No 'name' or 'package' parameters provided in task configuration and context.")))))
