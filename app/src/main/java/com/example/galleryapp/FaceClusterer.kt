package com.example.galleryapp

import kotlin.math.sqrt

class FaceClusterer(
    private val eps: Float = 0.45f, // Max distance (1 - 0.55 similarity) to be considered neighbors
    private val minPts: Int = 2     // Minimum faces to form a cluster
) {
    private val UNCLASSIFIED = 0
    private val NOISE = -1

    fun cluster(faces: List<FaceEntity>): List<List<FaceEntity>> {
        val clusters = mutableListOf<List<FaceEntity>>()
        val states = IntArray(faces.size) { UNCLASSIFIED }
        var clusterId = 1

        for (i in faces.indices) {
            if (states[i] != UNCLASSIFIED) continue

            val neighbors = getNeighbors(i, faces)
            if (neighbors.size < minPts) {
                states[i] = NOISE
            } else {
                val cluster = mutableListOf<FaceEntity>()
                expandCluster(i, neighbors, clusters, cluster, faces, states, clusterId)
                clusterId++
            }
        }
        return clusters
    }

    private fun expandCluster(
        pointIdx: Int,
        neighbors: MutableList<Int>,
        clusters: MutableList<List<FaceEntity>>,
        currentCluster: MutableList<FaceEntity>,
        faces: List<FaceEntity>,
        states: IntArray,
        clusterId: Int
    ) {
        states[pointIdx] = clusterId
        currentCluster.add(faces[pointIdx])

        var i = 0
        while (i < neighbors.size) {
            val neighborIdx = neighbors[i]

            if (states[neighborIdx] == NOISE) {
                states[neighborIdx] = clusterId
                currentCluster.add(faces[neighborIdx])
            } else if (states[neighborIdx] == UNCLASSIFIED) {
                states[neighborIdx] = clusterId
                currentCluster.add(faces[neighborIdx])

                val nextNeighbors = getNeighbors(neighborIdx, faces)
                if (nextNeighbors.size >= minPts) {
                    // Combine unique neighbors to continue expanding the cluster
                    for (n in nextNeighbors) {
                        if (!neighbors.contains(n)) {
                            neighbors.add(n)
                        }
                    }
                }
            }
            i++
        }
        clusters.add(currentCluster)
    }

    private fun getNeighbors(pointIdx: Int, faces: List<FaceEntity>): MutableList<Int> {
        val neighbors = mutableListOf<Int>()
        val targetVector = faces[pointIdx].faceVector ?: return neighbors

        for (i in faces.indices) {
            if (i == pointIdx) continue
            val compareVector = faces[i].faceVector ?: continue

            // Calculate distance: 1.0 - Cosine Similarity
            val distance = 1.0f - calculateCosineSimilarity(targetVector, compareVector)
            if (distance <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
    }

    // Standard Cosine Similarity (reused from your MainActivity)
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}