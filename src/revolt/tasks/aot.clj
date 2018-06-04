(ns revolt.tasks.aot
  (:require [clojure.tools.namespace.find :as tnfind]
            [revolt.utils :as utils]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn compile-namespaces
  [namespaces]
  (doseq [namespace namespaces]
    (log/debug "aot" namespace)
    (compile namespace)))

(defn invoke
  [opts ctx classpaths target]

  ;; ensure target is created
  (.mkdirs (io/file target))

  (utils/timed
   "AOT"
   (binding [*compile-path* target]
     (doseq [cp classpaths
             :when (.isDirectory cp)
             :let  [namespaces (tnfind/find-namespaces-in-dir cp)]]

       (compile-namespaces namespaces))))

  {:aot? true})
