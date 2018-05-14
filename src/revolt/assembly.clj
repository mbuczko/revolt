(ns revolt.assembly
  "This is a refined copy of JUXT's mach.pack.alpha.impl.assembly namespace
  containing functions assembling an uberjars."
  
  (:require
   [clojure.java.io :as io])
  (:import
   java.io.File
   [java.util.jar Attributes$Name JarEntry JarOutputStream Manifest]
   [java.util.zip ZipEntry ZipException ZipOutputStream]))

(defn- write! [stream file]
  (let [buf (byte-array 1024)]
    (with-open [in (io/input-stream file)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn- dupe? [t]
  (and (instance? ZipException t)
       (.startsWith (.getMessage t) "duplicate entry:")))

(defn- create-manifest [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defn- jarentry [path f & [dir?]]
  (JarEntry. (str (.replaceAll path "\\\\" "/") (when dir? "/"))))

(defn spit-jar! [jarpath files attr main]
  (let [manifest  (create-manifest main attr)
        jarfile   (io/file jarpath)
        dirs      (atom #{})
        parents*  #(iterate (comp (memfn getParent) io/file) %)
        parents   #(->> % parents* (drop 1)
                        (take-while (complement empty?))
                        (remove (partial contains? @dirs)))]
    (io/make-parents jarfile)
    (with-open [s (JarOutputStream. (io/output-stream jarfile) manifest)]
      (doseq [[jarpath srcpath] files]
        (let [e (jarentry jarpath srcpath)]
          (try
            (doseq [d (parents jarpath) :let [f (io/file d)]]
              (swap! dirs conj d)
              (doto s (.putNextEntry (jarentry d f true)) .closeEntry))
            (doto s (.putNextEntry e) (write! (io/input-stream srcpath)) .closeEntry)
            (catch Throwable t
              (if-not (dupe? t) (throw t) (println " warning: %s\n" (.getMessage t))))))))))
