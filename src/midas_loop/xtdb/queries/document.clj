(ns midas-loop.xtdb.queries.document
  (:require [midas-loop.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [xtdb.query]
            [midas-loop.xtdb.easy :as cxe]))

(defn delete [node document-id]
  (locking node
    (cond (nil? (cxe/entity node document-id))
          (write-error (str "Document does not exist: " document-id))

          :else
          (let [tx (cxq/delete** node {:document/id document-id :xt/id document-id})]
            (if (cxe/submit-tx-sync node tx)
              (write-ok)
              (write-error "Deletion failed"))))))

(defmethod xtdb.query/aggregate 'count-contents [_]
  (fn
    ([] 0)
    ([acc] acc)
    ([acc x]
     (cond (nil? x) acc
           (coll? x) (+ acc (count x))
           :else (+ acc 1)))))

(defn calculate-probas-stats [node document-id anno-type]
  (let [query {:find  '[?probas]
               :where ['[?d :document/sentences ?s]
                       '[?s :sentence/tokens ?t]
                       ['?t (keyword "token" anno-type) '?a]
                       ['?a (keyword anno-type "probas") '?probas]]
               :in    ['?d]}
        results (xt/q (xt/db node) query document-id)
        top-probas (->> results
                        (map (fn [[probas]]
                               (->> probas
                                    (sort-by second)
                                    last
                                    second))))]
    (if (= 0 (count results))
      -1
      (/ (reduce + top-probas)
         (count top-probas)))))

(defn calculate-sentence-probas-stats [node document-id]
  (let [query {:find  '[?probas]
               :where ['[?d :document/sentences ?s]
                       '[?s :sentence/tokens ?t]
                       '[?t :sentence/probas ?probas]]
               :in    ['?d]}
        results (xt/q (xt/db node) query document-id)
        top-probas (->> results
                        (map (fn [[probas]]
                               (->> probas
                                    (sort-by second)
                                    last
                                    second))))]
    (if (= 0 (count results))
      -1
      (/ (reduce + top-probas)
         (count top-probas)))))

(defn calculate-stats [node document-id]
  (let [query {:find  '[(count ?s) (count-contents ?t)
                        (count-contents ?xpos-gold)
                        (count-contents ?upos-gold)
                        (count-contents ?head-gold)]
               :where '[[?d :document/id ?id]
                        [?d :document/sentences ?s]
                        ;; subquery: find tokens
                        [(q {:find  [?t],
                             :where [[?s :sentence/tokens ?t]]
                             :in    [?s]} ?s)
                         ?t]

                        ;; subquery: find amount of gold xpos
                        [(q {:find  [?xpos]
                             :where [[?s :sentence/tokens ?t]
                                     [?t :token/xpos ?xpos]
                                     [?xpos :xpos/quality "gold"]]
                             :in    [?s]}
                            ?s)
                         ?xpos-gold]

                        ;; subquery: find amount of gold upos
                        [(q {:find  [?upos]
                             :where [[?d :document/sentences ?s]
                                     [?s :sentence/tokens ?t]
                                     [?t :token/upos ?upos]
                                     [?upos :upos/quality "gold"]]
                             :in    [?d]}
                            ?d)
                         ?upos-gold]

                        ;; subquery: find amount of gold head
                        [(q {:find  [?head]
                             :where [[?d :document/sentences ?s]
                                     [?s :sentence/tokens ?t]
                                     [?t :token/head ?head]
                                     [?head :head/quality "gold"]]
                             :in    [?d]}
                            ?d)
                         ?head-gold]
                        [(q {:find  [?deprel]
                             :where [[?d :document/sentences ?s]
                                     [?s :sentence/tokens ?t]
                                     [?t :token/deprel ?deprel]
                                     [?deprel :deprel/quality "gold"]]
                             :in    [?d]}
                            ?d)]]
               :in    '[?id]}]
    (let [res (xt/q (xt/db node) query document-id)
          [scount tcount xgr ugr hgr dgr] (first res)
          stats {:document/*sentence-count      scount
                 :document/*token-count         tcount
                 :document/*xpos-gold-rate      (/ xgr tcount)
                 :document/*upos-gold-rate      (/ ugr tcount)
                 :document/*head-gold-rate      (/ hgr tcount)
                 :document/*deprel-gold-rate    (/ dgr tcount)
                 :document/*xpos-mean-top-proba (calculate-probas-stats node document-id "xpos")
                 :document/*upos-mean-top-proba (calculate-probas-stats node document-id "upos")
                 :document/*head-mean-top-proba (calculate-probas-stats node document-id "head")
                 :document/*deprel-mean-top-proba (calculate-probas-stats node document-id "deprel")
                 :document/*sentence-mean-top-proba (calculate-sentence-probas-stats node document-id)}]
      (when-not (= 1 (count res))
        (throw (ex-info "ID produced a result set that did not have exactly one member!" {:document-id document-id})))
      (cxe/put node (merge (cxe/entity node document-id) stats)))))
