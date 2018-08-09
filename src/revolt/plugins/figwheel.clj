(ns revolt.plugins.figwheel
  (:require [clojure.tools.logging :as log]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.utils :as utils]
            [clojure.java.io :as io]))

(defn ensure-relative-builds-paths
  [builds target]
  (let [ensure-relative (partial utils/ensure-relative-path target)]
    (map #(-> %
              (update-in [:compiler :output-dir] ensure-relative)
              (update-in [:compiler :output-to] ensure-relative))
         builds)))

(defn init-plugin
  "Initializes figwheel plugin."

  [config]
  (reify Plugin
    (activate [this ctx]
      (require 'figwheel.main.api)

      (if-let [cljs-opts (:builds (.config-val ctx :revolt.task/cljs))]
        (let [assets (utils/ensure-relative-path (.target-dir ctx) "assets")
              builds (ensure-relative-builds-paths cljs-opts assets)
              fig-ns (find-ns 'figwheel.main.api)
              fig-fn (ns-resolve fig-ns 'start)

              build  (first (if-let [id (:build-id config)]
                              (filter #(= id (:id %)) builds)
                              builds))]

          (log/infof "Starting figwheel. build-id: %s." (:id build))

          ;; be sure assets dir already exists in case no plugin or task created it before
          (io/make-parents assets)

          (let [figwheel-conf (-> config
                                  (update :watch-dirs concat (:source-paths build))
                                  (update :css-dirs conj assets)
                                  (assoc  :mode :serve)
                                  (dissoc :build-id))]

            (fig-fn figwheel-conf (-> build
                                      (assoc  :id (:id build))
                                      (assoc  :options (:compiler build))
                                      (dissoc :compiler)
                                      (dissoc :source-paths)))))

        (log/error "revolt.task/cljs task needs to be configured.")))

    (deactivate [this ret]
      (log/debug "closing figwheel"))))
