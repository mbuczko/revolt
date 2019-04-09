(ns revolt.tasks.clean
  (:require [revolt.utils :as utils]
            [clojure.tools.logging :as log]))

(defn delete-files-recursively
  [fname & [silently]]
  (letfn [(delete-f [file]
            (when (.isDirectory file)
              (doseq [child-file (.listFiles file)]
                (delete-f child-file)))
            (clojure.java.io/delete-file file silently))]
    (delete-f (clojure.java.io/file fname))))

(defn invoke
  [ctx {:keys [extra-paths]} target]
  (utils/timed
   (str "CLEAN " target)
   (let [paths (into extra-paths [target "out"])]
     (doseq [p paths]
       (log/info "Cleaning path:" p)
       (delete-files-recursively p true))
     ctx)))
