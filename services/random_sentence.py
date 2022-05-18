from collections.abc import Iterable
import json
from flask import Flask, request
from typing import List, Tuple
import conllu
import numpy as np
import spacy

from time import sleep

app = Flask(__name__)


def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def tag_conllu(conllu_sentence: str) -> List[List[Tuple[str, float]]]:
    """
    Given an English sentence in conllu format, return POS tag probabilities for each token.
    """
    sentence = conllu.parse(conllu_sentence)[0]
    sentence = [t for t in sentence if is_plain_token(t)]
    labels = []
    for i, t in enumerate(sentence):
        softmax = {"B": 0.0, "O": 1.0}
        if i % 3 == 0:
            softmax["B"] = 0.95
            softmax["O"] = 0.05
        elif i % 3 == 1:
            softmax["B"] = 0.05
            softmax["O"] = 0.95
        elif i % 3 == 2:
            softmax["B"] = 0.50
            softmax["O"] = 0.50
        labels.append(softmax)

    return labels


@app.route("/", methods=["POST"])
def get():
    conllu_string = request.data.decode("utf-8")
    return json.dumps({"probabilities": tag_conllu(conllu_string)})


SAMPLE = """# sent_id = AMALGUM_reddit_beatty-47
# s_type = decl
# text = Its a money making model anymore.
1-2	Its	_	_	_	_	_	_	_	_
1	It	it	PRON	PRP	Case=Nom|Gender=Neut|Number=Sing|Person=3|PronType=Prs	6	nsubj	6:nsubj	Discourse=evaluation:86->85|Entity=(abstract-129)
2	s	be	AUX	VBZ	Mood=Ind|Number=Sing|Person=3|Tense=Pres|VerbForm=Fin	6	cop	6:cop	_
3	a	a	DET	DT	Definite=Ind|PronType=Art	6	det	6:det	Entity=(abstract-129
4	money	money	NOUN	NN	Number=Sing	6	compound	6:compound	Entity=(abstract-124)
5	making	make	VERB	VBG	VerbForm=Ger	6	compound	6:compound	_
6	model	model	NOUN	NN	Number=Sing	0	root	0:root	_
7	anymore	anymore	ADV	RB	Degree=Pos	6	advmod	6:advmod	Entity=abstract-129)|SpaceAfter=No
8	.	.	PUNCT	.	_	6	punct	6:punct	_
"""


def debug():
    tokens = conllu.parse(SAMPLE)[0]

    for token, probas in zip([t for t in tokens if is_plain_token(t)], tag_conllu(SAMPLE)):
        print(token["form"])
        print({x:y for x,y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    # debug()
    app.run(host="localhost", port=5556)
