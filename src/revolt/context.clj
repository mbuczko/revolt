(ns revolt.context
  (:require [clojure.tools.logging :as log]))

(defprotocol SessionContext
  (classpaths [this]   "Returns project classpaths.")
  (target-dir [this]   "Returns a project target directory.")
  (config-val [this k] "Returns a value from configuration map."))

(defonce session-context (atom {}))

(defn set-context!
  [context]
  (log/debug "setting session context")
  (reset! session-context context))

(defmacro with-context
  [context-sym & body]
  `(let [~context-sym ~`(deref session-context)] ~@body))
