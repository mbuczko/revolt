(ns revolt.bootstrap
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.java.classpath :as classpath]
            [revolt.plugin :refer [Plugin create-plugin] :as plugin]
            [revolt.utils :as utils]))

(defprotocol PluginContext
  (classpaths [this]   "Returns project classpaths.")
  (target-dir [this]   "Returns a project target directory.")
  (config-val [this k] "Returns a value from configuration map.")
  (terminate  [this]   "Sends a signal to deactivate all plugins."))


(defonce context (atom {}))

(def cli-options
  [["-c" "--config EDN" "EDN resource with revolt configuration."
    :default "revolt.edn"]

   ["-t" "--target DIR" "Target directory where to build artifacts."
    :default "target"]

   ["-a" "--activate-plugins PLUGINS" "Comma-separated list of plugins to activate."
    :default "revolt.rebel,revolt.nrepl"]])

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

(defn shutdown [plugins returns]
  (doseq [p plugins]
    (.deactivate p (get @returns p)))
  (System/exit 0))

(defn -main
  [& args]
  (let [params  (:options (cli/parse-opts args cli-options))
        target  (:target params)
        cpaths  (collect-classpaths target)]

    (if-let [config-edn (load-config (:config params))]
      (let [returns (atom {})
            plugins (map
                     #(let [kw (keyword %)] (plugin/initialize-plugin kw (kw config-edn)))
                     (utils/build-params-list params :activate-plugins))
            app-ctx  (reify PluginContext
                       (classpaths [this] cpaths)
                       (target-dir [this] target)
                       (config-val [this k] (k config-edn))
                       (terminate  [this] (shutdown plugins returns)))]

        ;; set global application context
        (reset! context app-ctx)

        ;; activate all the plugins sequentially one after another
        (doseq [p plugins]
          (when-let [ret (.activate p @context)]
            (swap! returns conj {p ret}))))

      (log/error "Configuration not found."))))
