(ns revolt.tasks.codox
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [revolt.utils :as utils]
            [codox.main]))


(defn read-project-info
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception ex
      (log/debug "No project information found in project.edn"))))

(defn invoke
  [opts target]
  (let [target-path  (utils/ensure-relative-path target "doc")
        project-path (utils/ensure-relative-path target "project.edn")
        project-info (read-project-info project-path)]

    (codox.main/generate-docs (-> project-info
                                  (merge opts)
                                  (assoc :output-path target-path)))))