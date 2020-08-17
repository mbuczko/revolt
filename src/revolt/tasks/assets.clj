(ns revolt.tasks.assets
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [revolt.utils :as utils])
  (:import  (java.io File)
            (java.nio.file StandardCopyOption)
            (java.nio.file Files)))

(def ^:const buffer-len 2048)
(def ^:private hex-digits (char-array "0123456789ABCDEF"))

;; stolen from clojurescript's utils
(defn- bytes-to-hex-str
  "Convert an array of bytes into a hex encoded string."
  [^bytes bytes]
  (loop [index (int 0)
         buffer (StringBuilder. (int (* 2 (alength bytes))))]
    (if (== (alength bytes) index)
      (.toString buffer)
      (let [byte (aget bytes index)]
        (.append buffer (aget ^chars hex-digits (bit-and (bit-shift-right byte 4) 0xF)))
        (.append buffer (aget ^chars hex-digits (bit-and byte 0xF)))
        (recur (inc index) buffer)))))

(def copy-options
  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))

(defn consume-input-stream
  [input-stream]
  (let [buffer (byte-array buffer-len)]
    (while (> (.read input-stream buffer 0 buffer-len) 0))))

(defn mk-sha256
  [file]
  (.getMessageDigest
    (doto
      (java.security.DigestInputStream.
        (io/input-stream file)
        (java.security.MessageDigest/getInstance "SHA-256"))
      consume-input-stream)))

(defn hashed-name
  [file]
  (let [digest (mk-sha256 file)]
    (str (bytes-to-hex-str (.digest digest)) "-" (.getName file))))

(defn excluded?
  [path exclude-paths]
  (some #(.startsWith path %) exclude-paths))

(defn copy-assets
  [path output-path exclude-paths]
  (let [assets-path (.toPath path)]
    (doseq [file (file-seq path)]
      (let [file-path (.toPath file)]
        (when (and (.isFile file)
                   (not (excluded? file-path exclude-paths)))
          (let [relative-output (.relativize assets-path file-path)
                destination (io/file output-path (.toString relative-output))]
            (io/make-parents destination)
            (Files/copy file-path (.toPath destination) copy-options)))))))

(defn fingerprint
  "Fingerprinting resources"
  [path]
  (let [assets-file (io/file path)
        assets-path (.toPath assets-file)]
    (filter
     (complement nil?)
     (for [file (file-seq assets-file)
           :when (and (.isFile file) (not (.endsWith (.getName file) ".DS_Store")))
           :let  [destination (io/file (.getParent file) (hashed-name file))]]
       (when (.renameTo file destination)
         (log/infof "%s => %s" file destination)
         [(str (.relativize assets-path (.toPath file)))
          (str (.relativize assets-path (.toPath destination)))])))))

(defn replace-all
  [text patterns]
  (loop [input text, pat patterns]
    (let [[pattern replacement] (first pat)]
      (if-not pattern
        input
        (recur (str/replace input pattern replacement)
               (rest pat))))))

(defn morph-resource
  [assets-kv extensions patterns file entry-name]
  (when (and file (not (assets-kv entry-name)))
    (if (some #(.endsWith (.toLowerCase entry-name) %) extensions)
      (let [tmp-file (File/createTempFile entry-name ".tmp")]
        (log/info "looking for assets in:" entry-name)
        (.deleteOnExit tmp-file)
        (spit tmp-file (replace-all (slurp file) patterns))
        tmp-file)
      file)))

(defn invoke
  [ctx {:keys [assets-paths exclude-paths update-with-exts]} classpaths target]
  (let [assets-path (utils/ensure-relative-path target "assets")
        extensions (map #(str "." (.toLowerCase %)) update-with-exts)]

    (utils/timed
     "COPYING ASSETS"
     (doseq [path (map io/file assets-paths)]
       (copy-assets path assets-path exclude-paths)))

    (utils/timed
     "FINGERPRINTING"
     (let [assets-kv (into {} (fingerprint assets-path))
           patterns  (map #(vector (re-pattern (first %)) (second %)) assets-kv)]

       (-> ctx
           (assoc  :assets assets-kv)
           (update :before-pack-fns conj (partial morph-resource assets-kv extensions patterns)))))))
