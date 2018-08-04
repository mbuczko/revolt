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
  [ctx {:keys [extra-namespaces]} classpaths target]

  (let [classes (utils/ensure-relative-path target "classes")]

    ;; ensure target is created
    (.mkdirs (io/file classes))

    (utils/timed
     "AOT"
     (binding [*compile-path* classes]
       (doseq [cp classpaths
               :when (.isDirectory cp)
               :let  [namespaces (tnfind/find-namespaces-in-dir cp)]]

         (compile-namespaces namespaces))

       ;; compile additional namespaces (if any)
       (compile-namespaces extra-namespaces)))

    (assoc ctx :aot? true)))
