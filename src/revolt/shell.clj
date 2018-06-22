(ns revolt.shell
  "Simple wrappers used to call shell commands as ordinary clojure functions."

  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def exec-str
  (comp (partial apply shell/sh) #(str/split % #" ")))

(defmacro list+
  [& forms]
  (->> forms
       (map (fn [x] (if (and (symbol? x)
                             (not (contains? &env x))
                             (not (resolve x)))
                      (name x)
                      x)))
       (cons `list)))

(defmacro sh
  [& args]
  `(some->> (list+ ~@args)
            (str/join " ")
            exec-str
            :out))

(defmacro git
  [& args]
  `(-> (sh "git" ~@args)
       str/trimr))
