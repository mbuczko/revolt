(ns revolt.tasks.codox
  (:require [revolt.utils :as utils]
            [codox.main]))

(defn invoke
  [opts target]
  (let [target-path  (utils/ensure-relative-path target "doc")
        project-info (utils/read-project-info target)]

    (codox.main/generate-docs (-> project-info
                                  (merge opts)
                                  (assoc :output-path target-path)))))
