(ns revolt.tasks.test
  (:require [metosin.bat-test.impl :as bat-test]
            [clojure.tools.logging :as log])
  (:import  [javazoom.jl.player Player]
            [java.io File FileInputStream]))


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

   ;; Sound notification?
   :notify true

   ;; Directories to watch
   :watch-directories ["src" "test"]})


(defn play!
  [file]
  (try
    (-> (.getResourceAsStream (clojure.lang.RT/baseLoader) file)
        java.io.BufferedInputStream.
        javazoom.jl.player.Player.
        .play)
    (catch Exception e
      (log/error "Cannot play a file: " (str file)))))

(defn invoke
  [ctx opts]
  (let [{:keys [fail error] :as result} (bat-test/run opts)]
    (when (:notify opts)
      (future (play!
               (cond
                 (> error 0) "notification/failure.mp3"
                 (> fail 0)  "notification/warning.mp3"
                 :default    "notification/success.mp3"))))
    (assoc ctx :test-report result)))
