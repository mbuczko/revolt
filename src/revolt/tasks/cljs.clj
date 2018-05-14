(ns revolt.tasks.cljs
  (:require [clojure.tools.logging :as log]
            [cljs.build.api]
            [revolt.utils :as utils]))


(defn invoke
  [input {:keys [builds]} classpaths target]
  (let [path (and input (.toString (.toPath input)))]
    (run!
     (fn [build]
       (log/debug "CLJS compiling" (or path (:id build)))
       (utils/timed
        (cljs.build.api/build (or path (:source-paths build)) (:compiler build))))
     (eduction
      (map #(update-in % [:compiler :output-dir] (partial utils/ensure-relative-path target)))
      builds))))
