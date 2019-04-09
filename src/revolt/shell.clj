(ns revolt.shell
  "Simple wrappers used to call shell commands as ordinary clojure functions."

  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn cmd
  [args]
  (let [{:keys [err out exit]} (apply shell/sh args)]
    (if (zero? exit) (str/trimr out) err)))

(defmacro sh
  [& forms]
  (let [args# (->> forms
                   (map #(if (and (symbol? %)
                                  (not (contains? &env %))
                                  (not (resolve %))) (name %) %))
                   (cons `list))]
    `(cmd ~args#)))

(defmacro git
  [& args]
  `(sh "git" ~@args))
