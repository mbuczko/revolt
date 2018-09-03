(ns revolt.tasks.sass
  (:require [sass4clj.core :as sass]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [revolt.utils :as utils])
  (:import  (java.io File)
            (java.nio.file Paths)))

(defn exclude-sass-pack-fn
  "A handler called before packing.
  Returns nil when entry ends with .sass which excludes this file from being jar-packed."

  [file entry-name]
  (when-not (or (.endsWith entry-name ".sass")
                (.endsWith entry-name ".scss"))
    file))

(defn invoke
  [ctx {:keys [source-path output-dir file sass-options]} classpaths target]

  (let [assets-path (utils/ensure-relative-path target (str "assets" File/separator output-dir))
        source-path (Paths/get source-path (make-array String 0))]

    ;; run SASS compilation for every single file found in
    ;; source-path, which file name does not start with _

    (let [path (io/file (str (or file source-path)))]

      (utils/timed
       (str "SASS " path)
       (doseq [file (file-seq path)]
         (when (and (.isFile file)
                    (not (.startsWith (.getName file) "_")))

           (let [relative-output (.relativize source-path (.toPath file))]
             (sass/sass-compile-to-file
              file
              (io/file assets-path (str/replace relative-output #"\.scss$" ".css"))
              sass-options)))))

      (update ctx :before-pack-fns conj exclude-sass-pack-fn))))
