(ns revolt.tasks.codox
  (:require [codox.main]))

(defonce default-options
  {
   ;; The directory where the documentation will be generated
   :output-path "target/doc"})

(defn invoke
  [opts]
  (codox.main/generate-docs opts))