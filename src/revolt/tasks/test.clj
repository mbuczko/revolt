(ns revolt.tasks.test
  (:require [metosin.bat-test.impl :as bat-test]))


(defonce default-options
  {
   ;; Regex used to select test namespaces
   :test-matcher #".*test"

   ;; Run tests parallel
   :parallel false

   ;; Reporting function
   ;; See https://github.com/metosin/bat-test/blob/master/src/metosin/bat_test.clj for other options
   :report :pretty

   ;; Function to filter the test vars
   :filter nil

   ;; Function to be called before running tests (after reloading namespaces)
   :on-start nil

   ;; Function to be called after running tests
   :on-end nil

   ;; Enable Cloverage coverage report
   :cloverage false

   ;; Cloverage options
   :cloverage-opts nil

   ;; Directories to watch
   :watch-directories ["src" "test"]})


(defn invoke
  [ctx opts]
  (assoc ctx :test-report (bat-test/run opts)))
