(ns revolt.plugins.figwheel
  (:require [clojure.tools.logging :as log]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.utils :as utils]
            [figwheel-sidecar.system :as figwheel]))

(defn ensure-relative-builds-paths
  [builds target]
  (let [ensure-relative (partial utils/ensure-relative-path target)]
    (map #(-> %
              (update-in [:compiler :output-dir] ensure-relative)
              (update-in [:compiler :output-to] ensure-relative)
              (assoc :figwheel true))
         builds)))

(defn init-plugin
  "Initializes figwheel plugin."

  [config]
  (reify Plugin
    (activate [this ctx]
      (if-let [cljs-opts (:builds (.config-val ctx :revolt.task/cljs))]
        (let [assets (utils/ensure-relative-path (.target-dir ctx) "assets")
              builds (ensure-relative-builds-paths cljs-opts assets)]

          (log/debug "Starting figwheel, brace yourself.")

          (figwheel/start-figwheel!
           (-> config
               (update :css-dirs conj assets)
               (assoc  :builds builds))))

        (log/error "revolt.task/cljs builder needs to be configured first.")))

    (deactivate [this ret]
      (log/debug "closing figwheel"))))
