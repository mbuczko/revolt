(ns revolt.tasks.sass
  (:require [sass4clj.core :as sass]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [revolt.utils :as utils])
  (:import  (java.io File)))

(defn invoke
  [{:keys [input-files options]} classpaths target]
  (run!
   (fn [[file relative-path]]
     (utils/timed
      (str "SASS " relative-path)
      (sass/sass-compile-to-file
       file
       (io/file (utils/ensure-relative-path target (str/replace relative-path #"\.scss$" ".css")))
       options)))
   (eduction
    (map (juxt io/file identity))
    input-files)))
