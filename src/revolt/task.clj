(ns revolt.task
  (:require [clojure.string :as str]
            [revolt.bootstrap :as bootstrap]
            [revolt.tasks.aot :as aot]
            [revolt.tasks.cljs :as cljs]
            [revolt.tasks.sass :as sass]
            [revolt.tasks.test :as test]
            [revolt.tasks.info :as info]
            [revolt.tasks.codox :as codox]
            [revolt.tasks.clean :as clean]
            [revolt.tasks.capsule :as capsule]
            [clojure.tools.logging :as log]))

(defprotocol Task
  (invoke   [this input ctx] "Runs task with provided input data and pipelined context.")
  (notify   [this path ctx]  "Handles notification with java.nio.file.Path typed argument.")
  (describe [this]           "Returns human readable task description."))

(defmulti create-task (fn [id opts classpaths target] id))

(defn create-task-with-args
  "A helper function to load a namespace denoted by namespace-part of
  provided qualified keyword, and create a task."

  [kw opts classpaths target]
  (if (qualified-keyword? kw)
    (let [ns (namespace kw)]
      (log/debug "initializing task" kw)
      (try
        (require (symbol ns))
        (create-task kw opts classpaths target)
        (catch Exception ex
          (log/errorf "Cannot initialize task %s: %s" kw (.getMessage ex)))))
    (log/errorf "Wrong keyword %s. Qualified keyword required." kw)))


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
    (fn [& [input context]]

      ;; as we operate on 2 optional parameter, 3 cases may happen:
      ;;
      ;; 1. we will get an input only, like (info {:version 0.0.2})
      ;;    this is a case where no context was given, and it should
      ;;    be created automatically.
      ;;
      ;; 2. we will get both: input and context.
      ;;    this is a case where context was given either directly
      ;;    along with input, eg. `(info {:version} ctx)` or task has
      ;;    partially defined input, like:
      ;;
      ;;     (partial capsule {:version 0.0.2})
      ;;
      ;;    and has been composed with other tasks:
      ;;
      ;;     (def composed-task (comp capsule info))
      ;;
      ;;    invocation of composed task will pass a context from one task
      ;;    to the other. tasks having input partially defined will get
      ;;    an input as a first parameter and context as a second one.
      ;;
      ;; 3. we will get a context only.
      ;;    this a slight variation of case 2. and may happen when task is
      ;;    composed together with others and has no partially defined input
      ;;    parameter. in this case task will be called with one parameter
      ;;    only - with an updated context.
      ;;
      ;;  to differentiate between case 1 and 3 a type check on first argument
      ;;  is applied. a ::ContextMap type indicates that argument is a context
      ;;  (case 3), otherwise it is an input argument (case 1).

      (let [context-as-input? (= (type input) ::ContextMap)
            context-map (or context
                            (when context-as-input? input)
                            ^{:type ::ContextMap} {})
            input-argument  (when-not context-as-input? input)]

        (cond

          ;; handle special arguments (keywords)
          (keyword? input-argument)
          (condp = input-argument
            :describe
            (.describe task)
            (throw (Exception. "Keyword parameter not recognized by task.")))

          ;; handle notifications
          (instance? java.nio.file.Path input-argument)
          (.notify task input-argument context-map)

          :else
          (.invoke task input-argument context-map))))))

(def require-task-cached (memoize require-task*))

(defmacro require-task
  [kw & [opt arg]]
  `(when-let [task# (require-task-cached ~kw)]
     (intern *ns*
             (or (and (= ~opt :as) '~arg) '~(symbol (name kw)))
             task#)))

(defmacro require-all [kws]
  `(when (coll? ~kws)
     [~@(map #(list `require-task %) kws)]))

;; built-in tasks

(defmethod create-task ::clean [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (clean/invoke input target)))
    (describe [this]
      "target directory cleaner")))

(defmethod create-task ::sass [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (sass/invoke (if (map? input)
                     (merge opts input)

                     ;; this is to filter configured :input-files by an input parameter (a Path).
                     ;; if filtered list is empty it means all configured :input-files should be
                     ;; recompiled. Otherwise only input-files left should be considered.

                     (update opts
                           :input-files
                           (fn [input-files path]
                             (or (seq (filter #(or (nil? path) (= path %)) input-files))
                                 input-files))
                           input))

                   classpaths
                   target)
      ctx)
    (notify [this path ctx]
      (.invoke this (.toString path) ctx))
    (describe [this]
      "SASS compiler")))

(defmethod create-task ::cljs [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (cljs/invoke (merge opts input) classpaths target)))
    (notify [this path ctx]
      (.invoke this path ctx))
    (describe [this]
      "CLJS compiler")))

(defmethod create-task ::test [_ opts classpaths target]
  (let [options (merge test/default-options opts)]
    (reify Task
      (invoke [this input ctx]
        (merge ctx (test/invoke (merge options input))))
      (notify [this path ctx]
        (.invoke this path ctx))
      (describe [this]
        "clojure.test runner"))))

(defmethod create-task ::codox [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (codox/invoke (merge opts input) ctx target)))
    (describe [this]
      "API doc generator")))

(defmethod create-task ::info [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (info/invoke (merge opts input) target)))
    (describe [this]
      "project info generator")))

(defmethod create-task ::capsule [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (capsule/invoke (merge opts input) ctx target)))
    (describe [this]
      "capsule packager")))

(defmethod create-task ::aot [_ opts classpaths target]
  (reify Task
    (invoke [this input ctx]
      (merge ctx (aot/invoke (merge opts input) ctx classpaths target)))
    (describe [this]
      "ahead-of-time compilation")))
