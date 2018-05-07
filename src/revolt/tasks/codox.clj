(ns revolt.tasks.codox
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [codox.main]
            [revolt.utils :as utils]))

(defn read-project-info
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception ex
      (log/debug "No project information found in project.edn"))))

(defn invoke
  [opts target]
  (let [project-path (utils/ensure-relative-path target "project.edn")
        project-info (read-project-info project-path)
        codox-opts (-> project-info
                       (merge opts)
                       (assoc :output-path (utils/ensure-relative-path target "doc")))]
    (codox.main/generate-docs codox-opts)))