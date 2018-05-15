(ns revolt.digest
  "This is a copy of JUXT's mach.pack.alpha.impl.elodin namespace
  containing helper functions for creating jars SHA-256 signatures."

  (:require
   [clojure.java.io :as io])
  (:import
   [java.nio.file Files Paths]))

(defn- signature
  [message-digest]
  (javax.xml.bind.DatatypeConverter/printHexBinary (.digest message-digest)))

(defn- consume-input-stream
  [input-stream]
  (let [buf-n 2048
        buffer (byte-array buf-n)]
    (while (> (.read input-stream buffer 0 buf-n) 0))))

(defn- sha256
  [file]
  (.getMessageDigest
   (doto
       (java.security.DigestInputStream.
        (io/input-stream file)
        (java.security.MessageDigest/getInstance "SHA-256"))
     consume-input-stream)))

(defn hash-derived-name
  [file]
  [(str (signature (sha256 file)) "-" (.getName file))])

(defn- paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(defn path-seq->str
  [path-seq]
  (str (paths-get path-seq)))

(defn file->path-seq
  [file]
  (->> file
       .toPath
       .iterator
       iterator-seq
       (map str)))

(defn full-path-derived-name
  [file]
  (file->path-seq file))
