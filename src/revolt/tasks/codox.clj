(ns revolt.tasks.codox
  (:require [revolt.utils :as utils]
            [codox.main]))

(defn invoke
  [opts ctx target]
  (let [target-path (utils/ensure-relative-path target "doc")]
    (codox.main/generate-docs 
     (-> ctx
         (select-keys [:name :group :version :description])
         (merge opts)
         (assoc :output-path target-path)))))
