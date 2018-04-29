(ns ^{:clojure.tools.namespace.repl/load false} revolt.plugins.watch
  (:require [clojure.java.io :as io]
            [clojure.java.classpath :as classpath]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.task :as task]
            [revolt.watcher :as watcher]
            [revolt.utils :as utils]))

(defn init-plugin
  "Initializes filesystem watcher plugin."

  [{:keys [excluded-paths explicit-paths on-change]}]
  (reify Plugin
    (activate [this ctx]
      (log/info "Starting filesystem watcher")

      (let [excludes   (utils/gather-paths excluded-paths)
            explicit   (utils/gather-paths explicit-paths)
            filesystem (java.nio.file.FileSystems/getDefault)
            classpaths (or (seq (map #(.toFile %) explicit))
                           (remove
                            #(contains? excludes (.toPath %))
                            (.classpaths ctx)))
            matchers   (reduce-kv (fn [m task pattern]
                                    (assoc m
                                           (.getPathMatcher filesystem pattern)
                                           (task/require-task task)))
                                  {}
                                  on-change)]

        (apply watcher/watch-files
               (conj classpaths
                     (fn [{:keys [file]}]
                       (let [path (.toPath file)]
                         (doseq [[matcher task] matchers]
                           (when (.matches matcher path)
                             (if task
                               (.invoke task file)
                               (log/error "No task {} found to react on change of: {}" task path))))))))))

    (deactivate [this ret]
      (log/debug "closing watcher")
      (watcher/stop-watching ret))))