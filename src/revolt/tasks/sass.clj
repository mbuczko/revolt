(ns revolt.tasks.sass
  (:require [sass4clj.core :as sass]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [revolt.utils :as utils]))


(defn invoke
  [input roots classpaths target]
  (run!
   (fn [[input-file relative-path]]
     (log/debug "SCSS compile: {}" (.toString input-file))
     (utils/timed
      (sass/sass-compile-to-file
       input-file
       (io/file target (str/replace relative-path #"\.scss$" ".css"))
       {})))
   (eduction
    (map (juxt io/resource identity))
    (or (when-let [path (and input (.toPath input))]
          (seq (filter #(.endsWith path %) roots)))
        roots))))
