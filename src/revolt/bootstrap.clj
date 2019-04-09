(ns revolt.bootstrap
  "A namespace gathering all the functions required to bootstrap and shutdown revolt.

  This namespace should be also passed in deps.edn `:main-opts` or used directly with
  clj --main option to get revolt auto-initialized."

  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.java.classpath :as classpath]
            [revolt.context :as ctx]
            [revolt.plugin :refer [Plugin create-plugin] :as plugin]
            [revolt.utils :as utils]
            [revolt.task :as task])
  (:import  [revolt.context SessionContext]))

(defonce status  (atom :not-initialized))

(def cli-options
  [["-c" "--config EDN" "EDN resource with revolt configuration."]

   ["-d" "--target DIR" "Target directory where to build artifacts."
    :default "target"]

   ["-p" "--plugins PLUGINS" "Comma-separated list of plugins to activate."]

   ["-t" "--tasks TASKS" "Comma-separated list of tasks to run."]])

(defn load-config
  [config-resource]
  (when config-resource
    (try
      (log/info "Configuration loaded from" config-resource)
      (read-string (slurp (io/resource config-resource)))
      (catch Exception ex
        (log/error (.getMessage ex))
        (System/exit -1)))))

(defn collect-classpaths
  "Returns project classpaths with target directory excluded."
  [target]
  (let [target-path (.. (io/file target) toPath toAbsolutePath)]
    (remove
     #(= target-path (.toPath %))
     (classpath/classpath-directories))))

(defn shutdown
  "Deactivates all the plugins."
  [plugins returns]
  (doseq [p plugins]
    (.deactivate p (get @returns p))))

(defn init-plugins
  "Initialize plugins passed in command-line."
  [plugins config]
  (for [[plugin opts] (utils/make-params-coll plugins "revolt.plugin")
        :let [kw (keyword plugin)]]
    (plugin/initialize-plugin kw (merge (kw config) opts))))

(defn -main
  [& args]
  (let [stopers (atom {})
        params  (:options (cli/parse-opts args cli-options))
        target  (:target params)
        cpaths  (collect-classpaths target)
        config  (load-config (:config params))
        plugins (init-plugins (:plugins params) config)
        app-ctx (ctx/set-context! (reify SessionContext
                                      (classpaths [this] cpaths)
                                      (target-dir [this] target)
                                      (config-val [this k] (k config))))]

    (add-watch status :status-watcher
               (fn [key reference old-state new-state]
                 (log/debug "session" new-state)
                 (when (= new-state :terminated)
                   (.halt (Runtime/getRuntime) 0))))

    ;; register a shutdown hook to be able to deactivate plugins on JVM shutdown
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do
                                  (shutdown plugins stopers)
                                  (reset! status :terminated))))

    (reset! status :initialized)

    ;; run sequentially required tasks
    (try
      (task/run-tasks-from-string (:tasks params))
      (catch Exception e
        (log/error "Task failed" e)
        (System/exit -1)))

    ;; activate all the plugins one after another (if any)
    (when (seq plugins)
      (doseq [p plugins]
        (when-let [ret (.activate p app-ctx)]
          (swap! stopers conj {p ret})))

      ;; wait till EOF
      (slurp *in*))
    (System/exit 0)))
