(ns revolt.tasks.jar
  (:require [revolt.utils :as utils]
            [clojure.java.io :as io]
            [clojure.tools.deps.alpha.reader :as tools.deps.reader]
            [clojure.tools.deps.alpha :as tools.deps]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import  (java.io File InputStream ByteArrayOutputStream)
            (java.net URLClassLoader)
            (java.util.jar Attributes$Name JarEntry JarOutputStream JarInputStream Manifest JarFile)
            (java.util.zip ZipEntry)))

(def default-mvn-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn default-output-jar
  "Returns default jar file locaction."

  [artifact-id version]
  (str "dist/" artifact-id (when version (str "-" version)) ".jar"))

(defn create-jar-entry
  "Creates a new entry in a jar out of given name and InputStream."

  [name ^InputStream input-stream ^JarOutputStream jar-stream]
  (log/debug "adding to jar:" name)
  (.putNextEntry jar-stream (ZipEntry. name))
  (io/copy input-stream jar-stream)
  (doto jar-stream (.closeEntry)))

(def system-classpaths
  (memoize (fn []
             (map (memfn getFile)
                  (.getURLs (URLClassLoader/getSystemClassLoader))))))

(defn add-to-jar
  "Adds new entry to jar file.

  Acts as a safe proxy to `create-jar-entry` by creating and safely
  closing entry InputStream."

  [name input ^JarOutputStream jar-stream]
  (with-open [input-stream (io/input-stream input)]
    (create-jar-entry name input-stream jar-stream)))

(defn add-manifest
  "Adds a manifest entry to the jar file."

  [^JarOutputStream jar-stream manifest-tuples & [main-class]]
  (let [manifest (Manifest.)
        attributes (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (when main-class
      (.put attributes Attributes$Name/MAIN_CLASS main-class))
    (doseq [[k v] manifest-tuples]
      (.put attributes (Attributes$Name. k) v))
    (let [baos (ByteArrayOutputStream.)]
      (.write manifest baos)
      (add-to-jar JarFile/MANIFEST_NAME (.toByteArray baos) jar-stream))))

(defn add-classes-from-artifact
  [^JarOutputStream jar-stream artifact class-p]
  (if-let [capsule-jar (some #(when (.contains % artifact) %) (system-classpaths))]
    (with-open [capsule-is  (io/input-stream capsule-jar)
                capsule-jis (JarInputStream. capsule-is)]
      (loop [^JarEntry entry (.getNextJarEntry capsule-jis)]
        (if-not entry
          jar-stream
          (let [entry-name (.getName entry)]
            (when (class-p entry)
              (create-jar-entry entry-name capsule-jis jar-stream))
            (recur (.getNextJarEntry capsule-jis))))))
    (throw (Exception. "No capsule jar found. Check for co.paralleluniverse/capsule in dependencies."))))

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

(defn before-add-path-hook
  "Pushes file through stack of handlers right before adding it to a jar.

  Each handler is a function of two parameters: a file and jar entry-name and
  should return a file (same as received or some other) which is passed to the
  next handler.

  Handler may return a nil which means that file should not be added to resulting
  uberjar (capsule)."

  [file entry-name handlers]
  (loop [input file, handlers handlers]
    (let [handler (first handlers)]
      (if-not handler
        input
        (recur (handler input entry-name)
               (rest handlers))))))

(defn add-paths
  [^JarOutputStream jar-stream classpaths aot? before-pack-fns]
  (try
    (doseq [[file name] (->> classpaths
                             (map io/file)
                             (filter (memfn isDirectory))
                             (mapcat dir->relative-entries))
            :when (not (and aot? (.endsWith name ".clj")))]

      (when-let [resource (before-add-path-hook file name before-pack-fns)]
        (add-to-jar name resource jar-stream)))

    (catch Exception e
      (log/error (.getMessage e))))
  jar-stream)

(defn build-jar
  [^JarOutputStream jar-stream application classpath deps {:keys [aot? before-pack-fns]}]
  (let [classpaths (str/split classpath (re-pattern File/pathSeparator))]
    (-> jar-stream
        (add-manifest [])
        (add-paths classpaths aot? before-pack-fns))))

(defn invoke
  [ctx {:keys [exclude-paths extra-paths output-jar name package version]} target]
  (let [artifact-id (or name (:name ctx))
        group-id    (or package (:package ctx))
        version     (or version (:version ctx))]

    (if-let [library (and artifact-id group-id (str group-id ":" artifact-id))]
      (utils/timed
       (str "JAR " library ":" (or version "<missing version>"))
       (let [deps-edn  (io/file "deps.edn")
             deps-map  (-> deps-edn
                           (tools.deps.reader/slurp-deps)
                           (update :paths utils/filter-paths (set exclude-paths))
                           (update :mvn/repos merge default-mvn-repos))
             deps-path (.. deps-edn toPath toAbsolutePath)
             classpath (tools.deps/make-classpath
                        (tools.deps/resolve-deps deps-map nil)
                        (utils/resolve-sibling-paths (:paths deps-map) deps-path)
                        {:extra-paths (->> ["classes" "assets"]
                                           (map (partial utils/ensure-relative-path target))
                                           (concat extra-paths)
                                           (map utils/ensure-absolute-path)
                                           (filter (complement nil?)))})
             output-jar (or output-jar (default-output-jar artifact-id version))
             jar-file   (io/file output-jar)]

         ;; ensure that all directories in a path are created
         (io/make-parents jar-file)

         (with-open [jar-stream (JarOutputStream. (io/output-stream jar-file))]
           (try
             (build-jar jar-stream
                        library
                        classpath
                        (:deps deps-map)
                        ctx)

             (catch Throwable t
               (log/errorf "Error while creating a jar file: %s" (.getMessage t))))

           ;; return libraty location as a result
           (assoc ctx :jar-file output-jar))))

      (throw (Exception. "No 'name' or 'package' parameters provided in task configuration and context.")))))
