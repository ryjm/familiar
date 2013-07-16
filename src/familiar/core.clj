(ns familiar.core
  ;(:gen-class)
   (:require [familiar
               [dbconfig :refer :all]
               [db :refer :all]
               [validator :refer :all]
               [graph :refer :all]]
             [korma
               [core :refer :all]
               [db :refer :all]]
             [lobos
               [connectivity :as lc]
               [core :as l]
               [schema :as ls]]
             [clojure 
               [walk :as walk]
               [pprint :refer [pprint]]]
             [clojure.java.jdbc :as jdb]
             [clojure.java.jdbc.sql :as sql]
             [swiss-arrows.core :refer :all]
             [clj-time
               [core :refer :all :rename {extend elongate}]
               [coerce :refer :all]
               [format :refer :all]
               [local :refer :all]]
             [loom
               [graph :refer :all]
               [alg :refer :all]
               [gen :refer :all]
               [attr :refer :all]
               [label :refer :all]
               [io :refer :all]]))

(declare str->key with-str-args display-vars active-expt active-expt-name)

;;;;;;;;;;;;;;;
;; Experiments
;;

(def db (atom (h2 {:db "data/default/default.db"})))
(def korma-db (atom (create-db @db)))

(defn open!
  "Changes active experiment to that with the specified name"
  [file]
  (reset! db (h2 {:db (str "data/" file  "/" file ".db")}))
  (reset! korma-db (create-db @db))
  (default-connection @korma-db)
  (create-tables @db))

(open! "default")

;;;;;;;;;;;;;
;; Variables
;;

(defn- tag-var-
  [varname & tags]
  (assert (seq (get-field :name variable varname))
          (str "No variable by the name " varname "."))
  (apply create-if-missing tag tags)
  (for [name tags]
    (insert variable_tag
      (values {:tag_id      (get-field :id tag name)
               :variable_id (get-field :id variable varname)}))))

(defmacro tag-var
  "Adds tags to variable"
  [& args]
  `(tag-var- ~@(map str args)))

(defn- new-var-
  [[varname validator default] & {:keys [expt time-res unit tags]
                                    :or {expt active-expt
                                         time-res "date"
                                         unit ""
                                         tags "()"}}]
  (assert ((eval (read-string validator)) (read-string default))
          "Given default fails validator.")
  (assert (not (nil? (read-string default)))
          "nil default is reserved for predicates. Deal with it.")
  (insert variable
    (values {:name varname
             :default default
             :unit unit
             :time-res time-res
             :fn validator
             :deps "nil"}))
  (apply tag-var- varname (read-string tags)))

(defmacro new-var
  "Adds variable to experiment.
     Example: (new-var robot boolean? false)
     Optional arguments:
     :expt - name of an experiment (defaults to loaded experiment)
     :time-res - time resolution (defaults to date, can be date-time)
     :unit - a string representing the unit of measure
     :tags - a sequence of strings with which to tag the variable"
  [& exprs]
  `(with-str-args new-var- ~exprs))

(defn- new-pred-
  [[predname function depend] & {:keys [expt time-res unit tags]
                                   :or {expt active-expt
                                        time-res "date"
                                        unit ""
                                        tags "()"}}]
  (insert variable
    (values {:name predname
             :default "nil"
             :unit unit
             :time-res time-res
             :fn function
             :deps depend}))
  (apply tag-var- predname (read-string tags)))

(defmacro new-pred
  "Adds predicate to experiment.
     Example:
     (new-pred accomplished 
               (fn [t] (>= (value productivity t) 3))
               productivity)"
  [predname function & depend]
    (new-pred- [(str predname) (str function) (str (vec (map str depend)))]))

(defn- display-
  [tags]
  (let [tags (if (seq tags)
               (partial some (set (map str tags)))
               (constantly true))]
    (->> (select variable
           (with tag)
           (where {:default [not= "nil"]}))
         (map (fn [t] (update-in t [:tag]
                                 #(map :name %))))
         (filter #(tags (:tag %)))
         (map #(select-keys % [:default :fn :unit :tag :name]))
         pprint)))

(defmacro display
  "Displays info for variables in active experiment that match tags, or all
     variables if no arguments."
  [& tags]
  (display- (map str tags)))

(defn- validate [varname value]
  (let [validator (-> (get-field :fn variable varname)
                      read-string
                      eval)]
    (validator value)))

;;;;;;;;
;; Data
;;

(defn datum
  "Adds a single instance of variable."
  [varname value & {:keys [expt instant]
                      :or {expt active-expt, instant @active-time}}]
  (let [timeslice (slice instant varname)]
    (assert (not (nil? (get-field :default variable varname)))
            "Cannot add data for predicates")
    (assert (validate varname (read-string value))
            (str value " is invalid for " varname))
    (assert (no-concurrent-instance? timeslice varname)
            (str varname " already has value at " timeslice))
    (insert instance
      (values {:time timeslice
               :value value
               :variable_id (get-field :id variable varname)}))))

(defn- data-
  [coll & {:keys [expt instant]
             :or {expt active-expt, instant @active-time}}]
  (transaction
    (try (doall
      (->> (partition 2 coll)
           (map #(concat % [:expt expt :instant instant]))
           (map #(apply datum %))))
      (catch Throwable e (println (.getMessage e))
                         (rollback)))))

(defmacro data
  "Adds instances of variables with values.
     Example:
     (add-data mice 6 cats 2 dogs 0)"
  [& exprs]
  `(with-str-args data- ~exprs))

(defn- erase-
  [coll & {:keys [expt instant]
             :or {expt active-expt, instant @active-time}}]
  (let [slices 
        (map (comp (partial slice instant) :name)
             (select variable 
               (fields :name) 
               (where {:name [in coll]})))
        ids 
        (map :id
             (select variable
               (fields :id)
               (where {:name [in coll]})))]
    (transaction
      (delete instance 
        (fields :time :variable_id)
        (where {:time [in (set slices)]
                :variable_id [in ids]})))))

(defmacro erase
  "Erases data for given variables at active time/given time."
  [& exprs]
  `(with-str-args erase- ~exprs))

(defn missing
  "Displays all variables with no instance for the
     time pixel matching the active time/given time."
  [& {:keys [expt instant]
        :or {expt active-expt, instant @active-time}}]
  (->> (map :name (select variable
                    (fields :name)
                    (where {:default [not= "nil"]})))
       (filter #(no-concurrent-instance? (slice instant %) %))))

(defn entered
  "Displays values for variables with an instance within
     the time pixel matching the active time/given time."
  [& {:keys [expt instant]
        :or {expt active-expt, instant @active-time}}]
  (remove (set (missing :expt expt :instant instant))
          (map :name (select variable (fields :name)
                       (where {:default [not= "nil"]})))))

(defn- defaults-
  [variables & {:keys [expt instant]
                  :or {expt active-expt, instant @active-time}}]
  (-<>> (select variable
          (fields :name :default)
          (where {:name [in variables]
                  :default [not= "nil"]})
          (order :name))
        (map :default)
        (interleave (sort variables))
        (data- <> :expt expt :instant instant)))

(defmacro defaults
  "Allows given variables to take on their default values"
  [& exprs]
  `(with-str-args defaults- ~exprs))

;; TODO (defn review

(defn change-day 
  "Sets active time n days ahead or behind."
  [n]
  (swap! active-time
         #(plus % (days n))))

(defn datagen 
  "Generates fake data for every delta-t in a variable from instant 
     to (plus instant duration) according to func. Remember that every
     value func could return should be a string."
  [varname func delta-t duration 
   & {:keys [expt instant]
        :or {expt active-expt, instant @active-time}}]
  (let [[start end] (sort [instant (plus instant duration)])
        instants    (range-instants start end delta-t)
        time-res    (keyword (get-field :time-res variable varname))]
    (doall
      (map #(->> (unparse-time  %)
                 (datum varname (func) :instant))
           instants))))

;;;;;;;;;;;
;; Helpers
;;

(defn help
  "Informs you what's what."
  []
  (->> (for [[n v] (ns-interns 'familiar.core)]
         [(str "+ " n) "\n    " (:doc (meta v))])
       (remove #(nil? (nth % 2)))
       (interpose "\n")
       flatten
       println))

(defn- str->key [s] 
  (->> (str s)
       (replace {\space \-})
       (apply str) 
       .toLowerCase
       keyword))

(defmacro with-str-args [f exprs]
  (let [[args opts] (split-with (complement keyword?)
                                exprs)
        args (map str args)
        opts (map #(if (keyword? %) % (str %)) opts)]
    `(~f '~args ~@opts)))

#_(defn -main
  [& args]
  (alter-var-root #'*read-eval* (constantly false))
  (println "Launching Familiar"))
