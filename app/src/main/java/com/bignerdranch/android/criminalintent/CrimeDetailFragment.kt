package com.bignerdranch.android.criminalintent

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bignerdranch.android.criminalintent.databinding.FragmentCrimeDetailBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

private const val scaleFactor = 1.0f
private const val DATE_FORMAT = "EEE, MMM, dd"
private const val APP_TAG = "CrimeDetailFragment"

class CrimeDetailFragment : Fragment() {

    companion object {
        private const val MAX_FONT_SIZE = 90F
    }
    private lateinit var loadingDialog: LoadingDialog

    private var _binding: FragmentCrimeDetailBinding? = null
    private val binding
        get() = checkNotNull(_binding) {
            "Cannot access binding because it is null. Is the view visible?"
        }

    private var detectionList = mutableListOf<Int>(0 ,0 ,0)

    private val args: CrimeDetailFragmentArgs by navArgs()

    private val crimeDetailViewModel: CrimeDetailViewModel by viewModels {
        CrimeDetailViewModelFactory(args.crimeId)
    }

    private val selectSuspect = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        uri?.let { parseContactSelection(it) }
    }

    private var photoName: String? = null

    private fun getBitmapFromFile(photoFile: File): Bitmap? {
        if (photoFile.exists()) {
            return BitmapFactory.decodeFile(photoFile.path)
        }
        return null
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { didTakePhoto: Boolean ->
        if (didTakePhoto && photoName != null) {
            //Hide the previous face detection count
            binding.tvFacesCount.visibility = View.INVISIBLE
            //Only run if face detection is checked
            val photoFile = File(requireContext().applicationContext.filesDir, photoName)
            var bitmap:Bitmap? = rotateImageFromCamera(photoFile.path)
            if (bitmap != null) {
                photoFile.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 85)
            }
            if(bitmap == null) bitmap = getBitmapFromFile(photoFile)

            //TODO: Solve loading dialog issues
            for (i in 0 until detectionList.size){
                detectionList[i] = 0
            }
            if(binding.enableFaceDetection.isChecked || binding.enableContourDetection.isChecked) {
                if (bitmap != null) {
                    detectionList[0] = 1
                    runFaceDetection(photoFile, bitmap, binding.enableContourDetection.isChecked, binding.enableFaceDetection.isChecked)
                }
            }
            if(binding.enableMeshDetection.isChecked){
                if (bitmap != null) {
                    detectionList[1] = 1
                    runFaceMeshDetection(photoFile, bitmap)
                }
            }
            if(binding.enableSelfieSegmentation.isChecked) {
                if (bitmap != null) {
                    detectionList[2] = 1
                    runSelfieSegmentation(photoFile, bitmap)
                }
            }
            if(detectionList.sum() > 0) loadingDialog.show()
            crimeDetailViewModel.updateCrime { oldCrime ->
                var list = mutableListOf<String?>()
                list.addAll(oldCrime.crimeImageNames)

                var imageCount = oldCrime.imageTakeCount
                Log.d("Working", "The current count is $imageCount")
                Log.d("Working", "The index is ${imageCount % oldCrime.crimeImageNames.size}")
                Log.d("Working", list.toString())
                list[imageCount % oldCrime.crimeImageNames.size] = photoName
                Log.d("Working", list.toString())
                imageCount++
                oldCrime.copy(photoFileName = photoName, imageTakeCount = imageCount, crimeImageNames = list)
            }

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        loadingDialog = LoadingDialog(requireActivity())
        _binding =
            FragmentCrimeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            crimeTitle.doOnTextChanged { text, _, _, _ ->
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(title = text.toString())
                }
            }

            crimeSolved.setOnCheckedChangeListener { _, isChecked ->
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(isSolved = isChecked)
                }
            }

            enableFaceDetection.setOnClickListener {
                resetCheckboxes(0)
            }
            enableContourDetection.setOnClickListener {
                resetCheckboxes(1)
            }
            enableMeshDetection.setOnClickListener {
                resetCheckboxes(2)
            }
            enableSelfieSegmentation.setOnClickListener {
                resetCheckboxes(3)
            }

            crimeSuspect.setOnClickListener {
                selectSuspect.launch(null)
            }

            val selectSuspectIntent = selectSuspect.contract.createIntent(
                requireContext(),
                null
            )
            crimeSuspect.isEnabled = canResolveIntent(selectSuspectIntent)

            crimeCamera.setOnClickListener {
                photoName = "IMG_${Date()}.JPG"
                val photoFile = File(
                    requireContext().applicationContext.filesDir,
                    photoName
                )
                val photoUri = FileProvider.getUriForFile(
                    requireContext(),
                    "com.bignerdranch.android.criminalintent.fileprovider",
                    photoFile
                )

                takePhoto.launch(photoUri)
            }

            val captureImageIntent = takePhoto.contract.createIntent(
                requireContext(),
                null
            )
            crimeCamera.isEnabled = canResolveIntent(captureImageIntent)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                crimeDetailViewModel.crime.collect { crime ->
                    crime?.let { updateUi(it) }
                }
            }
        }

        setFragmentResultListener(
            DatePickerFragment.REQUEST_KEY_DATE
        ) { _, bundle ->
            val newDate =
                bundle.getSerializable(DatePickerFragment.BUNDLE_KEY_DATE) as Date
            crimeDetailViewModel.updateCrime { it.copy(date = newDate) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun resetCheckboxes(keepIndex: Int){
        for (i in 0 ..3){
            if(i == keepIndex) continue
            else {
                when (i) {
                    0 -> binding.enableFaceDetection.isChecked = false
                    1 -> binding.enableContourDetection.isChecked = false
                    2 -> binding.enableMeshDetection.isChecked = false
                    3 -> binding.enableSelfieSegmentation.isChecked = false
                }
            }
        }
    }

    private fun updateUi(crime: Crime) {
        Log.d(APP_TAG, "Updating the UI")
        binding.apply {
            if (crimeTitle.text.toString() != crime.title) {
                crimeTitle.setText(crime.title)
            }
            crimeDate.text = crime.date.toString()
            crimeDate.setOnClickListener {
                findNavController().navigate(
                    CrimeDetailFragmentDirections.selectDate(crime.date)
                )
            }

            crimeSolved.isChecked = crime.isSolved

            crimeReport.setOnClickListener {
                val reportIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getCrimeReport(crime))
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        getString(R.string.crime_report_subject)
                    )
                }
                val chooserIntent = Intent.createChooser(
                    reportIntent,
                    getString(R.string.send_report)
                )
                startActivity(chooserIntent)
            }

            crimeSuspect.text = crime.suspect.ifEmpty {
                getString(R.string.crime_suspect_text)
            }
            for (i in 0..3){
                updatePhoto(i, crime.crimeImageNames[i])
            }
        }
    }

    private fun getCrimeReport(crime: Crime): String {
        val solvedString = if (crime.isSolved) {
            getString(R.string.crime_report_solved)
        } else {
            getString(R.string.crime_report_unsolved)
        }

        val dateString = DateFormat.format(DATE_FORMAT, crime.date).toString()
        val suspectText = if (crime.suspect.isBlank()) {
            getString(R.string.crime_report_no_suspect)
        } else {
            getString(R.string.crime_report_suspect, crime.suspect)
        }

        return getString(
            R.string.crime_report,
            crime.title, dateString, solvedString, suspectText
        )
    }

    private fun rotateImage(source: Bitmap, angle: Int): Bitmap? {
        val matrix = Matrix()
        matrix.postRotate(angle.toFloat())
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            matrix, true
        )
    }

    private fun rotateImageFromCamera(photoFilePath: String): Bitmap? {
        // Create and configure BitmapFactory
        // Create and configure BitmapFactory
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(photoFilePath, bounds)
        val opts = BitmapFactory.Options()
        val bm = BitmapFactory.decodeFile(photoFilePath, opts)
        // Read EXIF Data
        // Read EXIF Data
        var exif: ExifInterface? = null
        try {
            exif = ExifInterface(photoFilePath)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val orientString =
            exif!!.getAttribute(ExifInterface.TAG_ORIENTATION)
        val orientation =
            orientString?.toInt() ?: ExifInterface.ORIENTATION_NORMAL
        var rotationAngle = 0
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90
        if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180
        if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270

        val matrix = Matrix()
        matrix.setRotate(rotationAngle.toFloat(), bm.width.toFloat() / 2, bm.height.toFloat() / 2)

        return Bitmap.createBitmap(bm, 0, 0, bounds.outWidth, bounds.outHeight, matrix, true)
    }

    private fun runSelfieSegmentation(photoFile: File, initBitmap: Bitmap){
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        val segmenter = Segmentation.getClient(options)
        segmenter.process(InputImage.fromBitmap(initBitmap, 0))
            .addOnSuccessListener { segmentationMask ->
                Log.d(APP_TAG, "Successful Selfie Segmentation")
                val mask = segmentationMask.buffer
                val maskWidth = segmentationMask.width
                val maskHeight = segmentationMask.height
                var resultBitmap: Bitmap = Bitmap.createBitmap(
                    maskColorsFromByteBuffer(mask, maskWidth, maskHeight), maskWidth, maskHeight, Bitmap.Config.ARGB_8888
                )
                var loadedBM: Bitmap?  = getBitmapFromFile(photoFile)
                val bitmap: Bitmap = loadedBM?.copy(Bitmap.Config.ARGB_8888, true) ?: initBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(bitmap)
                val transformationMatrix = Matrix()
                transformationMatrix.reset()
                transformationMatrix.setScale(scaleFactor, scaleFactor)
                val postScaleWidthOffset = 0f
                val postScaleHeightOffset = 0f

                transformationMatrix.postTranslate(-postScaleWidthOffset, -postScaleHeightOffset)

                val scaleX = initBitmap.height * 1f / maskWidth
                val scaleY = initBitmap.width * 1f / maskHeight
                transformationMatrix.preScale(scaleX, scaleY)
                canvas.drawBitmap(resultBitmap, transformationMatrix, null)

                //bitmap.recycle()
                // Reset byteBuffer pointer to beginning, so that the mask can be redrawn if screen is refreshed
                mask.rewind()
//                val resultBitmap = Bitmap.createBitmap( maskWidth , maskHeight , Bitmap.Config.ARGB_8888 )
//                resultBitmap.copyPixelsFromBuffer( mask )
                photoFile.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 85)
                var index: Int? = null
                crimeDetailViewModel.updateCrime { oldCrime ->
                    index = (oldCrime.imageTakeCount - 1) % oldCrime.crimeImageNames.size
                    oldCrime
                }
                if(index != null){
                    Log.d(APP_TAG, "The photolocal is ${photoFile.name}")
                    Log.d(APP_TAG, "The index phoyy is: $index")
                    updatePhoto(index!!, photoFile.name)
                }
                detectionList[2] = 0
                if(detectionList.sum() == 0) loadingDialog.dismiss()
                Log.d(APP_TAG, "The detection list is: $detectionList")
            }
            .addOnFailureListener { e -> // Task failed with an exception
                detectionList[2] = 0
                if(detectionList.sum() == 0) loadingDialog.dismiss()
                e.printStackTrace()
                showToast("Error running selfie segmentation")
            }
    }

    private fun maskColorsFromByteBuffer(byteBuffer: ByteBuffer, maskWidth: Int, maskHeight: Int): IntArray {
        @ColorInt val colors =
            IntArray(maskWidth * maskHeight)
        for (i in 0 until maskWidth * maskHeight) {
            val backgroundLikelihood = 1 - byteBuffer.float
            if (backgroundLikelihood > 0.9) {
                colors[i] = Color.argb(128, 255, 0, 255)
            } else if (backgroundLikelihood > 0.2) {
                val alpha = (182.9 * backgroundLikelihood - 36.6 + 0.5).toInt()
                colors[i] = Color.argb(alpha, 255, 0, 255)
            }
        }
        return colors
    }

    private fun runFaceMeshDetection(photoFile: File, initBitmap: Bitmap){
        val optionsBuilder = FaceMeshDetectorOptions.Builder()
        val detector = FaceMeshDetection.getClient(optionsBuilder.build())
        detector.process(InputImage.fromBitmap(initBitmap, 0))
            .addOnSuccessListener { faceMeshs ->
                Log.d(APP_TAG, "Successful Face Mesh Detection")
                detectionList[1] = 0
                if (faceMeshs.isEmpty()) {
                    Log.d(APP_TAG, "No face mesh found")
                    showToast("No face mesh found")
                    if(detectionList.sum() == 0) loadingDialog.dismiss()
                    Log.d(APP_TAG, "The detection list is: $detectionList")
                }
                else{
                    var bitmap: Bitmap?  = getBitmapFromFile(photoFile)
                    if(bitmap !== null){
                        bitmap = drawFaceMeshResults(bitmap, faceMeshs)
                        photoFile.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 85)
                    }
                    //Force face photo update at that point
                    var index: Int? = null
                    crimeDetailViewModel.updateCrime { oldCrime ->
                        index = (oldCrime.imageTakeCount - 1) % oldCrime.crimeImageNames.size
                        oldCrime
                    }
                    if(index != null){
                        Log.d(APP_TAG, "The photolocal is ${photoFile.name}")
                        Log.d(APP_TAG, "The index phoyy is: $index")
                        updatePhoto(index!!, photoFile.name)
                    }
                    if(detectionList.sum() == 0) loadingDialog.dismiss()
                    Log.d(APP_TAG, "The detection list is: $detectionList")
                }
            }
            .addOnFailureListener { e -> // Task failed with an exception
                detectionList[1] = 0
                if(detectionList.sum() == 0) loadingDialog.dismiss()
                e.printStackTrace()
                showToast("Error running selfie segmentation")
            }
    }
    private fun runFaceDetection(photoFile: File, initBitmap: Bitmap, withContour: Boolean, withBox: Boolean) {
        Log.d(APP_TAG, "Running Face Detection")
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(if(withContour) FaceDetectorOptions.CONTOUR_MODE_ALL else FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build()
        val detector: FaceDetector = FaceDetection.getClient(options)
        detector.process(InputImage.fromBitmap(initBitmap, 0))
            .addOnSuccessListener { faces ->
                Log.d(APP_TAG, "Successful Face Detection")
                detectionList[0] = 0
                if (faces.isEmpty()) {
                    Log.d(APP_TAG, "No face found")
                    showToast("No faces found")
                    if(detectionList.sum() == 0) loadingDialog.dismiss()
                    Log.d(APP_TAG, "The detection list is: $detectionList")

                    if(withBox){
                        binding.tvFacesCount.visibility = View.VISIBLE
                        binding.tvFacesCount.text =
                            getString(R.string.crime_faces_detected, faces.size.toString())
                    }
                } else {
                    Log.d(APP_TAG, "Faces Found: ${faces.size}")
                    var bitmap: Bitmap? = getBitmapFromFile(photoFile)
                    //IF face detection enabled use bounding box
                    if (withBox) {
                        if (bitmap != null) {
                            bitmap = drawDetectionResult(bitmap, faces)
                            photoFile.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 85)
                        }
                        binding.tvFacesCount.visibility = View.VISIBLE
                        binding.tvFacesCount.text =
                            getString(R.string.crime_faces_detected, faces.size.toString())

                    }
                    if (withContour) {
                        if (bitmap != null) {
                            bitmap = drawContourResults(bitmap, faces)
                            photoFile.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 85)
                        }
                        //binding.previousCrimePhoto1.setImageBitmap(bitmap)
                    }
                    //Force face photo update at that point
                    var index: Int? = null
                    crimeDetailViewModel.updateCrime { oldCrime ->
                        index = (oldCrime.imageTakeCount - 1) % oldCrime.crimeImageNames.size
                        oldCrime
                    }
                    if (index !== null) {
                        Log.d(APP_TAG, "The photolocal is ${photoFile.name}")
                        Log.d(APP_TAG, "The index phoyy is: $index")
                        updatePhoto(index!!, photoFile.name)
                    }
                    if(detectionList.sum() == 0) loadingDialog.dismiss()
                    Log.d(APP_TAG, "The detection list is: $detectionList")
                }
            }
            .addOnFailureListener { e -> // Task failed with an exception
                detectionList[0] = 0
                if(detectionList.sum() == 0) loadingDialog.dismiss()
                e.printStackTrace()
                showToast("Error running face detection")
            }

    }

    private fun showToast(message: String) {
        Toast.makeText(
            requireContext(),
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun File.writeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int) {
        outputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.flush()
            Log.d(APP_TAG, "Done writing")
        }
    }

    private fun drawDetectionResult(
        bitmap: Bitmap,
        detectionResults: List<Face>
    ): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()
        pen.textAlign = Paint.Align.LEFT
        Log.d(APP_TAG, "In draw results")

        detectionResults.forEachIndexed {index, elt ->

            // draw bounding box
            pen.color = Color.RED
            pen.strokeWidth = 20F
            pen.style = Paint.Style.STROKE
            val box = elt.boundingBox
            canvas.drawRect(box, pen)

            val tagSize = Rect(0, 0, 0, 0)

            // calculate the right font size
            pen.style = Paint.Style.FILL_AND_STROKE
            pen.color = Color.YELLOW
            pen.strokeWidth = 2F

            pen.textSize = MAX_FONT_SIZE
            pen.getTextBounds("Face", 0, "Face".length, tagSize)
            val fontSize: Float = pen.textSize * box.width() / tagSize.width()

            // adjust the font size so texts are inside the bounding box
            if (fontSize < pen.textSize) pen.textSize = fontSize

            var margin = (box.width() - tagSize.width()) / 2.0F
            if (margin < 0F) margin = 0F
            canvas.drawText(
                "Face ${index+1}", box.left + margin,
                box.top + tagSize.height().times(1F), pen
            )
        }
        return outputBitmap
    }

    private fun updatePaintColorByZValue(
        paint: Paint,
        canvas: Canvas,
        visualizeZ: Boolean,
        rescaleZForVisualization: Boolean,
        zInImagePixel: Float,
        zMin: Float,
        zMax: Float
    ) {
        if (!visualizeZ) {
            return
        }

        // When visualizeZ is true, sets up the paint to different colors based on z values.
        // Gets the range of z value.
        val zLowerBoundInScreenPixel: Float
        val zUpperBoundInScreenPixel: Float
        if (rescaleZForVisualization) {
            zLowerBoundInScreenPixel = Math.min(-0.001f,zMin)
            zUpperBoundInScreenPixel = Math.max(0.001f, zMax)
        } else {
            // By default, assume the range of z value in screen pixel is [-canvasWidth, canvasWidth].
            val defaultRangeFactor = 1f
            zLowerBoundInScreenPixel = -defaultRangeFactor * canvas.width
            zUpperBoundInScreenPixel = defaultRangeFactor * canvas.width
        }
        val zInScreenPixel: Float = zInImagePixel
        if (zInScreenPixel < 0) {
            // Sets up the paint to be red if the item is in front of the z origin.
            // Maps values within [zLowerBoundInScreenPixel, 0) to [255, 0) and use it to control the
            // color. The larger the value is, the more red it will be.
            var v = (zInScreenPixel / zLowerBoundInScreenPixel * 255).toInt()
            v = v.coerceIn(0, 255)
            paint.setARGB(255, 255, 255 - v, 255 - v)
        } else {
            // Sets up the paint to be blue if the item is behind the z origin.
            // Maps values within [0, zUpperBoundInScreenPixel] to [0, 255] and use it to control the
            // color. The larger the value is, the more blue it will be.
            var v = (zInScreenPixel / zUpperBoundInScreenPixel * 255).toInt()
            v = v.coerceIn(0, 255)
            paint.setARGB(255, 255 - v, 255 - v, 255)
        }
    }

    private fun drawLine(canvas: Canvas, point1: PointF3D, point2: PointF3D, myPaint: Paint, zMin: Float, zMax: Float) {
        updatePaintColorByZValue(
            myPaint,
            canvas,
            /* visualizeZ= */true,
            /* rescaleZForVisualization= */true,
            (point1.z + point2.z) / 2,
            zMin,
            zMax)
        canvas.drawLine(
            point1.x,
            point1.y,
            point2.x,
            point2.y,
            myPaint
        )
    }

    private fun drawFaceMeshResults(
    bitmap: Bitmap,
    detectionResults: List<FaceMesh>): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val myPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        Log.d(APP_TAG, "Drawing face mesh resuults")
        myPaint.color = Color.parseColor("#4DFFFFFF")

        detectionResults.forEach() { faceMesh ->
            Log.d(APP_TAG, "in facemesh")
            val points = faceMesh.allPoints
            val triangles = faceMesh.allTriangles

            var zMin = Float.MAX_VALUE
            var zMax = Float.MIN_VALUE
            for (point in points) {
                zMin = Math.min(zMin, point.position.z)
                zMax = Math.max(zMax, point.position.z)
            }

            // Draw face mesh points
            for (point in points) {
                updatePaintColorByZValue(
                    myPaint,
                    canvas,
                    true,
                    true,
                    point.position.z,
                    zMin,
                    zMax
                )
                canvas.drawCircle(
                    point.position.x,
                    point.position.y,
                    8.0f,
                    myPaint
                )
            }
            for (triangle in triangles) {
                val point1 = triangle.allPoints[0].position
                val point2 = triangle.allPoints[1].position
                val point3 = triangle.allPoints[2].position
                drawLine(canvas, point1, point3, myPaint, zMin, zMax)
                drawLine(canvas, point1, point3, myPaint, zMin, zMax)
                drawLine(canvas, point1, point3, myPaint, zMin, zMax)
            }
        }
        return outputBitmap
    }

    private fun drawContourResults(
        bitmap: Bitmap,
        detectionResults: List<Face>
    ): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val myPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        myPaint.color = Color.parseColor("#0096FF")
        myPaint.strokeWidth = 20F

        Log.d(APP_TAG, "The detection results size is ${detectionResults.size}")
        detectionResults.forEach { face ->

            // Draws a circle at the position of the detected face, with the face's track id below.
            val x: Int = face.boundingBox.centerX()
            val y: Int = face.boundingBox.centerY()
            canvas.drawCircle(
                x.toFloat(),
                y.toFloat(),
                10.0f,
                myPaint
            )
            Log.d(APP_TAG, "In face")
            val contour = face.allContours
            Log.d(APP_TAG, "contour face list size ${contour.size}")
            for (faceContour in contour) {
                for (point in faceContour.points) {
                    val px: Float = point.x
                    val py: Float = point.y
                    canvas.drawCircle(
                        px,
                        py,
                        10.0f,
                        myPaint
                    )
                }
            }
        }
        return outputBitmap
    }

    private fun parseContactSelection(contactUri: Uri) {
        val queryFields = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)

        val queryCursor = requireActivity().contentResolver
            .query(contactUri, queryFields, null, null, null)

        queryCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                val suspect = cursor.getString(0)
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(suspect = suspect)
                }
            }
        }
    }

    private fun canResolveIntent(intent: Intent): Boolean {
        val packageManager: PackageManager = requireActivity().packageManager
        val resolvedActivity: ResolveInfo? =
            packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        return resolvedActivity != null
    }

    private fun updatePhoto(index: Int, photoFileName: String?) {
        Log.d("Working", "The update photo index is $index")
        val imageView = when(index) {
            0-> binding.crimePhoto
            1-> binding.previousCrimePhoto1
            2-> binding.previousCrimePhoto2
            3-> binding.previousCrimePhoto3
            else -> null
        } ?: return
        Log.d("WORKING", "THe photo id is $photoFileName")
        Log.d("WORKING", "The check val is: ${imageView.tag != photoFileName}")
        //Commented this out so it works on updating the photo with face detection results
        //if (imageView.tag != photoFileName) {
            Log.d("WORKING", "check1")
            val photoFile = photoFileName?.let {
                File(requireContext().applicationContext.filesDir, it)
            }
            if (photoFile?.exists() == true) {
                Log.d("WORKING", "check2")
                imageView.doOnLayout { measuredView ->
                    val scaledBitmap = getScaledBitmap(
                        photoFile.path,
                        measuredView.width,
                        measuredView.height
                    )
                    imageView.setImageBitmap(scaledBitmap)
                    imageView.tag = photoFileName
                    imageView.contentDescription =
                        getString(R.string.crime_photo_image_description)
                }
            } else {
                imageView.setImageBitmap(null)
                imageView.tag = null
                imageView.contentDescription =
                    getString(R.string.crime_photo_no_image_description)
            }
        //}
    }
}
