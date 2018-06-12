(ns revolt.bootstrap
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.java.classpath :as classpath]
            [revolt.context :refer :all]
            [revolt.plugin :refer [Plugin create-plugin] :as plugin]
            [revolt.utils :as utils]
            [revolt.task :as task]))

(defonce status  (atom :not-initialized))

(def cli-options
  [["-c" "--config EDN" "EDN resource with revolt configuration."
    :default "revolt.edn"]

   ["-d" "--target DIR" "Target directory where to build artifacts."
    :default "target"]

   ["-p" "--plugins PLUGINS" "Comma-separated list of plugins to activate."]

   ["-t" "--tasks TASKS" "Comma-separated list of tasks to run."]])

(defn load-config
  [config-resource]
  (when-let [res (io/resource config-resource)]
    (try
      (let [config (slurp res)]
        (read-string config))
      (catch Exception ex
        (log/error (.getMessage ex))))))

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


(defn -main
  [& args]
  (let [params (:options (cli/parse-opts args cli-options))
        target (:target params)
        config (:config params)
        cpaths (collect-classpaths target)]

    (if-let [config-edn (load-config config)]
      (let [returns (atom {})
            plugins (for [[plugin opts] (utils/make-params-coll (:plugins params) "revolt.plugin")
                          :let [kw (keyword plugin)]]
                      (plugin/initialize-plugin kw (merge (kw config-edn) opts)))
            app-ctx (set-context! (reify SessionContext
                                    (classpaths [this] cpaths)
                                    (target-dir [this] target)
                                    (config-val [this k] (k config-edn))))]

        (add-watch status :status-watcher
                   (fn [key reference old-state new-state]
                     (log/debug "session" new-state)
                     (when (= new-state :terminated)
                       (.halt (Runtime/getRuntime) 0))))

        ;; register a shutdown hook to be able to deactivate plugins on JVM shutdown
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(do
                                      (shutdown plugins returns)
                                      (reset! status :terminated))))

        (reset! status :initialized)

        ;; run sequentially required tasks
        (when-let [result (task/run-tasks-from-string (:tasks params))]
          (log/info result))

        ;; activate all the plugins one after another (if any)
        (when (seq plugins)
          (doseq [p plugins]
            (when-let [ret (.activate p app-ctx)]
              (swap! returns conj {p ret})))

          ;; wait till EOF
          (slurp *in*))
        (System/exit 0))
      (log/error "Configuration not found."))))
