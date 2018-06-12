(ns revolt.tasks.codox
  (:require [revolt.utils :as utils]
            [codox.main]))

(defn validate-params
  "Validates existence of `:name` and `:version` in provided
  parameters. Returns all parameters in case of successful validation.
  Throws exception otherwise."

  [{:keys [name version] :as params}]
  (if-not (and name version)
    (throw (Exception. "No name or version provided."))
    params))

(defn invoke
  [opts ctx target]
  (let [target-path (utils/ensure-relative-path target "doc")]
    (codox.main/generate-docs
     (-> ctx
         (merge opts)
         (validate-params)
         (select-keys [:name :version :description :documents :namespaces :package])
         (assoc :output-path target-path)))

    ;; return location of generated docs
    {:codox target-path}))
