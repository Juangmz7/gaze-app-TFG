package mongo

import "go.mongodb.org/mongo-driver/v2/bson"

// readKeysetRanges reads up to limit rows matching base, taking the ranges in order
// until the page is full. find runs one bounded query for a filter and the rows still
// missing. The ranges are disjoint and follow the sort, so their concatenation is the
// sorted list after the cursor. Each one is an equality prefix plus one range on the
// hinted index: a single bounded IXSCAN that starts at the cursor, with no SORT stage,
// so a page costs at most len(ranges) seeks of limit keys each, never a skip.
//
// One $or of the same ranges would be a single query, but the planner may answer it
// with one IXSCAN over the union of the bounds (from the first key equal to the
// cursor's first value) plus a filter, walking every earlier row with that value:
// O(offset) again (measured on the comment lists, task 48).
func readKeysetRanges[T any](base bson.D, ranges []bson.D, limit int, find func(filter bson.D, remaining int) ([]T, error)) ([]T, error) {
	rows := make([]T, 0, limit)
	for _, keyset := range ranges {
		filter := append(append(bson.D{}, base...), keyset...)
		page, err := find(filter, limit-len(rows))
		if err != nil {
			return nil, err
		}
		rows = append(rows, page...)
		if len(rows) >= limit {
			break
		}
	}

	return rows, nil
}
