package com.example.galleryapp

import io.objectbox.Box
import io.objectbox.BoxStore

class FaceClusterer(
    private val faceBox: Box<FaceEntity>,
    private val personBox: Box<PersonEntity>,
    private val eps: Float = 0.63f,  // Immich defaults to 0.5 for cosine distance
    private val minPts: Int = 3    // Immich defaults to a minimum of 3 recognized faces
) {
    // The staging area for extracted faces that haven't been clustered yet
    private val clusteringQueue = mutableListOf<FaceEntity>()

    /**
     * Called after FaceNet extracts vectors. Pushes faces into the staging queue.
     */
    fun enqueueFacesForClustering(faces: List<FaceEntity>) {
        clusteringQueue.addAll(faces)
    }

    /**
     * The main Incremental DBSCAN loop.
     * Run this when all active face detection jobs are finished.
     */
    fun processClusteringQueue() {
        if (clusteringQueue.isEmpty()) return

        // Holds faces that don't meet the minPts threshold initially
        val deferredFaces = mutableListOf<FaceEntity>()
        val iterator = clusteringQueue.iterator()

        while (iterator.hasNext()) {
            val targetFace = iterator.next()

            // Find neighbors (To be implemented in Step 2)
            val similarFaces = findSimilarFaces(targetFace)

            if (similarFaces.size < minPts) {
                // Not enough matches to form a cluster right now.
                // Defer it to check again at the very end.
                deferredFaces.add(targetFace)
            } else {
                // Threshold met. Assign to existing person or create a new one.
                // (To be implemented in Step 3)
                assignOrCluster(targetFace, similarFaces)
            }

            // Remove the face from the active queue once processed
            iterator.remove()
        }

        // Retry deferred faces to see if they can join newly created clusters.
        // (To be implemented in Step 4)
        retryDeferredFaces(deferredFaces)
    }

    // --- STUBS FOR UPCOMING STEPS ---

    /**
     * Queries ObjectBox for the nearest vectors and filters them strictly by the EPS threshold.
     */
    private fun findSimilarFaces(targetFace: FaceEntity): List<FaceEntity> {
        val targetVector = targetFace.faceVector ?: return emptyList()
        val similarFaces = mutableListOf<FaceEntity>()

        // 1. Use ObjectBox HNSW Index to quickly find the top 100 closest faces.
        // Make sure to import your generated FaceEntity_ class (e.g., import com.example.galleryapp.FaceEntity_)
        val maxNeighborsToCheck = 100
        val nearestFaces = faceBox.query(FaceEntity_.faceVector.nearestNeighbors(targetVector, maxNeighborsToCheck))
            .build()
            .find()

        // 2. Filter the results strictly by your EPS distance threshold
        for (compareFace in nearestFaces) {
            // Skip comparing the target face to itself if it's already in the database
            if (compareFace.id == targetFace.id) continue

            val compareVector = compareFace.faceVector ?: continue

            // 3. Calculate distance: 1.0 - Cosine Similarity (reusing your exact logic)
            val distance = 1.0f - calculateCosineSimilarity(targetVector, compareVector)

            // 4. If the distance is smaller than or equal to EPS, it is a valid neighbor
            if (distance <= eps) {
                similarFaces.add(compareFace)
            }
        }

        return similarFaces
    }

    // Keep your exact implementation of calculateCosineSimilarity here:
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble()))).toFloat()
    }

    /**
     * Determines whether to add the face to an existing person or create a new one.
     */
    private fun assignOrCluster(targetFace: FaceEntity, similarFaces: List<FaceEntity>) {
        // 1. Filter the neighborhood to see if any faces already have a 'Person' assigned
        val assignedNeighbors = similarFaces.filter { it.person.target != null }

        if (assignedNeighbors.isNotEmpty()) {
            // 2. Existing cluster found!
            // If neighbors belong to different people, Immich resolves this by picking the Person
            // attached to the single face with the highest similarity score.
            val targetVector = targetFace.faceVector ?: return

            val mostSimilarNeighbor = assignedNeighbors.maxByOrNull { neighbor ->
                val neighborVector = neighbor.faceVector ?: return@maxByOrNull -1f
                calculateCosineSimilarity(targetVector, neighborVector)
            }

            // 3. Assign our target face to this existing Person
            val existingPerson = mostSimilarNeighbor?.person?.target
            if (existingPerson != null) {
                targetFace.person.target = existingPerson
                faceBox.put(targetFace) // Save the update to the database
            }
        } else {
            // 4. No existing person found among neighbors. Create a new cluster!
            val newPerson = PersonEntity(name = "Unknown Person")

            // Generate a thumbnail path for the UI (Implementation stubbed below)
            newPerson.coverFaceImagePath = generatePersonThumbnail(targetFace)

            // Save the new person to the DB so it gets an ID
            personBox.put(newPerson)

            // 5. Assign the target face to the new person
            targetFace.person.target = newPerson
            faceBox.put(targetFace)

            // 6. Assign ALL the unassigned neighbors to this new person as well
            for (neighbor in similarFaces) {
                neighbor.person.target = newPerson
                faceBox.put(neighbor) // Update the neighbor in the database
            }
        }
    }

    /**
     * Generates a thumbnail for the newly created person cluster.
     */
    private fun generatePersonThumbnail(face: FaceEntity): String {
        // In your final implementation, you can use the face.boundingBox to crop the original
        // image and save a high-quality 112x112 thumbnail specifically for the UI.

        // For now, if you already save cropped faces somewhere, you can return that path:
        return face.faceImagePath
    }

    /**
     * Re-evaluates faces that initially failed to meet the clustering threshold.
     */
    private fun retryDeferredFaces(deferredFaces: List<FaceEntity>) {
        for (deferredFace in deferredFaces) {
            // 1. Check the neighborhood again. The database has likely changed
            // since this face was first evaluated in Step 1.
            val similarFaces = findSimilarFaces(deferredFace)

            // 2. Check if any of these similar faces now belong to a Person
            val hasAssignedNeighbor = similarFaces.any { it.person.target != null }

            // 3. If it now has a person nearby, OR if it finally meets the minPts
            // threshold, we process it just like a normal face.
            if (hasAssignedNeighbor || similarFaces.size >= minPts) {
                assignOrCluster(deferredFace, similarFaces)
            }

            // If it still fails both conditions, it remains unassigned (Noise).
            // The next time the user adds new photos, this face will sit in the database
            // and might act as a neighbor to pull new faces into a cluster!
        }
    }


}