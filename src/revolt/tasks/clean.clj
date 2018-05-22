(ns revolt.tasks.clean
  (:require [revolt.utils :as utils]))

(defn delete-files-recursively
  [fname & [silently]]
  (letfn [(delete-f [file]
            (when (.isDirectory file)
              (doseq [child-file (.listFiles file)]
                (delete-f child-file)))
            (clojure.java.io/delete-file file silently))]
    (delete-f (clojure.java.io/file fname))))

(defn invoke
  [input target]
  (utils/timed "CLEAN"
   (delete-files-recursively target true)))
