(ns revolt.tasks.codox
  (:require [revolt.utils :as utils]
            [codox.main]))

(defn invoke
  [opts target]
  (let [target-path (utils/ensure-relative-path target "doc")]
    (codox.main/generate-docs 
     (assoc opts :output-path target-path))))
