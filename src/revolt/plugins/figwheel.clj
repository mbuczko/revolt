(ns revolt.plugins.figwheel
  (:require [clojure.tools.logging :as log]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.utils :as utils]
            [clojure.java.io :as io]
            [figwheel.main.api :as figwheel]))

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
      (if-let [cljs-opts (:builds (.config-val ctx :revolt.task/cljs))]
        (let [assets (utils/ensure-relative-path (.target-dir ctx) "assets")
              builds (ensure-relative-builds-paths cljs-opts assets)
              build  (first (if-let [id (:build-id config)]
                              (filter #(= id (:id %)) builds)
                              builds))]

          (log/infof "Starting figwheel. build-id: %s." (:id build))

          ;; be sure assets dir already exists in case no plugin or task created it before
          (io/make-parents assets)

          (if build
            (let [figwheel-conf (-> config
                                    (update :watch-dirs concat (:source-paths build))
                                    (update :css-dirs conj assets)
                                    (assoc  :mode :serve)
                                    (dissoc :build-id))

                  build-conf (-> build
                                 (assoc  :id (:id build)
                                         :options (:compiler build))
                                 (dissoc :compiler
                                         :source-paths))]

              (figwheel/start figwheel-conf build-conf)
              (:id build))

            (log/error "Build not found")))
        (log/error "revolt.task/cljs task needs to be configured.")))

    (deactivate [this build-id]
      (when build-id
        (log/infof "stopping figwheel. build-id: %s." build-id)
        (figwheel/stop build-id)))))
