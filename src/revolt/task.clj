(ns revolt.task
  (:require [clojure.string :as str]
            [revolt.utils :as utils]
            [revolt.bootstrap :as bootstrap]
            [revolt.tasks.cljs :as cljs]
            [revolt.tasks.sass :as sass]
            [revolt.tasks.test :as test]
            [revolt.tasks.info :as info]
            [revolt.tasks.codox :as codox]
            [clojure.tools.logging :as log]))

(defprotocol Task
  (invoke [this input] "Starts a task with given input file(s)."))

(defmulti create-task (fn [id opts classpaths target] id))


(defn create-task-with-args
  "A helper function to load a namespace denoted by namespace-part of
  provided qualified keyword, and create a task."

  [kw opts classpaths target]
  (if (qualified-keyword? kw)
    (let [ns (namespace kw)]
      (log/debug "initializing task" kw)
      (require (symbol ns))
      (create-task kw opts classpaths target))
    (log/error "Wrong keyword {}. Qualified keyword required." kw)))


(defn require-task*
  "Creates a task instance from qualified keyword.

  Loads a corresponding namespace from qualified keyword and invokes
  `create-task` multi-method with keyword as a dispatch value,
  passing task options, project classpaths and project target
  building directory as arguments."

  [kw]
  (let [ctx  @bootstrap/context
        task (create-task-with-args kw
                                    (.config-val ctx kw)
                                    (.classpaths ctx)
                                    (.target-dir ctx))]
    (fn [& input]
      (.invoke task (first input)))))

(def require-task (memoize require-task*))

;; built-in tasks

(defmethod create-task ::sass [_ opts classpaths target]
  (reify Task
    (invoke [this input]
      (sass/invoke input (:input-files opts) classpaths target))))

(defmethod create-task ::cljs [_ opts classpaths target]
  (reify Task
    (invoke [this input]
      (cljs/invoke input opts classpaths target))))

(defmethod create-task ::test [_ opts classpaths target]
  (let [options (merge test/default-options opts)]
    (reify Task
      (invoke [this input]
        (test/invoke opts)))))

(defmethod create-task ::codox [_ opts classpaths target]
  (reify Task
    (invoke [this input]
      (codox/invoke (merge opts input) target))))

(defmethod create-task ::info [_ opts classpaths target]
  (reify Task
    (invoke [this input]
      (info/invoke (merge opts input) target))))