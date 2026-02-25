#!/usr/bin/env python3

import openpyxl
from sklearn.metrics import cohen_kappa_score


def load_scores(path):
    wb = openpyxl.load_workbook(path, data_only=True)
    all_scores = {}          # {team: {qid:0/1}}
    for sheetname in wb.sheetnames:
        if not sheetname.startswith("Team"):
            continue
        sheet = wb[sheetname]
        for row in sheet.iter_rows(min_row=2, values_only=True):
            qid = row[0]
            if qid is None:          # empty row
                continue
            # try the "Annotator Score" column first (index 5), else use Pass/Fail
            score = row[5]
            if score is None:
                if row[3] == "Pass (1)":
                    score = 1
                elif row[4] == "Fail (0)":
                    score = 0
            if score is None:
                continue   # not evaluated
            all_scores.setdefault(sheetname, {})[qid] = int(score)
    return all_scores


def main():
    m1 = load_scores("eval/matteo.xlsx")
    m2 = load_scores("eval/nicco.xlsx")

    # build list of paired scores
    common = []
    for team, scores in m1.items():
        if team in m2:
            for qid, s1 in scores.items():
                s2 = m2[team].get(qid)
                if s2 is not None:
                    common.append((s1, s2))

    if not common:
        print("Nessun punteggio in comune; accertati di aver compilato i fogli.")
        return

    r1, r2 = zip(*common)
    kappa_all = cohen_kappa_score(r1, r2)
    print(f"kappa totale (tutti i quesiti): {kappa_all:.3f}")

    # per quesito
    by_q = {}
    idx = 0
    for team, scores in m1.items():
        if team in m2:
            for qid, s1 in scores.items():
                if qid in m2[team]:
                    by_q.setdefault(qid, []).append((s1, m2[team][qid]))
    print("kappa per quesito:")
    for qid, pairs in by_q.items():
        v1, v2 = zip(*pairs)
        print(f"  {qid}: {cohen_kappa_score(v1, v2):.3f}")


if __name__ == "__main__":
    main()
