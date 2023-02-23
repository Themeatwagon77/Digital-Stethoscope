package com.example.audiorecorder


import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.*
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.room.Room
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bottom_sheet.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


const val REQUEST_CODE = 200

class MainActivity : AppCompatActivity(), Timer.OnTimerTickListener {



    private lateinit var amplitudes: ArrayList<Float>
    private var permissions = arrayOf(android.Manifest.permission.RECORD_AUDIO)
    private var permissionGranted = false


    private lateinit var audiorecorder: android.media.AudioRecord
    private lateinit var AudioTracker: AudioTrack
    private var dirPath = ""
    private var filename = ""
    private var isRecording = false
    private var isPaused = false
    private lateinit var mediaRecorder: MediaRecorder

    private val socAudioStateReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onSocStateChanged(
                    intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                            == AudioManager.SCO_AUDIO_STATE_CONNECTED
                )
            }
        }
    }


    private var startScoRecord = false
    private var stopScoRecord = false


    private var duration = ""

    private lateinit var vibrator: Vibrator

    private lateinit var timer: Timer

    private lateinit var db: AppDatabase

    var m_thread: Thread? = null

    private var auto = 3


    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>


    override fun onCreate(savedInstanceState: Bundle?) {


        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)



        permissionGranted = ActivityCompat.checkSelfPermission(
            this,
            permissions[0]
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted)
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)


        db = Room.databaseBuilder(
            this, AppDatabase::class.java,
            "audioRecords"
        ).build()

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.peekHeight = 0
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        timer = Timer(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        m_thread = Thread { loopBack() }

        btnRecord.setOnClickListener {
            when {
                isPaused -> resumeRecorder()
                isRecording -> pauseRecorder()
                else -> startRecord()

            }
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        btnList.setOnClickListener {
            // TODO
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        btnDone.setOnClickListener {

            stopRecorder()
            Toast.makeText(this, "Record Saved", Toast.LENGTH_SHORT).show()

            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            bottomSheetBG.visibility = View.VISIBLE
            filenameInput.setText(filename)
        }

        if (auto == 3) {
            auto()
        }
        button6.setOnClickListener {
            button6()
        }

        button5.setOnClickListener {
            button5()
        }


        switch2.setOnClickListener {
            switchmic()
        }

        btnCancel.setOnClickListener {
            File("$dirPath$filename.mp3").delete()
            dismiss()

        }

        btnOk.setOnClickListener {
            dismiss()
            save()
        }
        bottomSheetBG.setOnClickListener {
            File("$dirPath$filename.mp3").delete()
            dismiss()
        }

        btnDelete.setOnClickListener {
            stopRecorder()
            File("$dirPath$filename.mp3").delete()
            Toast.makeText(this, "Record Deleted", Toast.LENGTH_SHORT).show()

        }
        btnDelete.isClickable = false

        registerReceiver(
            socAudioStateReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )





    }


    override fun onPause() {
        super.onPause()
        AudioTracker.stop()}

    override fun onStop() {
        super.onStop()
        AudioTracker.stop()}





    private fun save(){
        val newFilename = filenameInput.text.toString()
        if(newFilename != filename){
            var newFile = File("$dirPath$filename.mp3")
            File("$dirPath$filename.mp3").renameTo(newFile)
        }

        var filePath = "$dirPath$filename.mp3"
        var timestamp = Date().time
        var ampsPath = "$dirPath$filename"



        var record = AudioRecord(newFilename,filePath,timestamp,duration,ampsPath)

        GlobalScope.launch {
            db.audioRecordDao().insert(record)
        }


    }

    private fun dismiss(){
        bottomSheetBG.visibility = View.GONE
        hideKeyboard(filenameInput)

        Handler(Looper.getMainLooper()).postDelayed({
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        },100)
    }
    private fun hideKeyboard(view: View){
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken,0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(requestCode == REQUEST_CODE)
            permissionGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED
    }

    private fun pauseRecorder(){
        mediaRecorder.pause()
        isPaused = true
        btnRecord.setImageResource(R.drawable.ic_record)
        timer.pause()
    }

    private fun resumeRecorder(){
        mediaRecorder.resume()
        isRecording = true
        isPaused = false
        btnRecord.setImageResource(R.drawable.ic_pause)
        timer.start()
    }


    var m_suppressor: NoiseSuppressor? = null
    var m_canceler: AcousticEchoCanceler? = null



    var bassBoost = null

    var m_isRun = true
    var bufferSize = 0
    var SAMPLE_RATE = 44100
    lateinit var buffer: ByteArray
    var ix = 0

    fun loopBack() {




        try {
            bufferSize = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

//          if (bufferSize <= BUF_SIZE) {
//              bufferSize = BUF_SIZE;
//          }
            Log.i("LOG", "Initializing Audio Record and Audio Playing objects")
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }






            audiorecorder = android.media.AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )








            AudioTracker = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            AudioTracker!!.playbackRate = SAMPLE_RATE
            audiorecorder!!.startRecording()
            Log.i("LOG", "Audio Recording started")

            Log.i("LOG", "Audio Playing started")
            buffer = ByteArray(bufferSize)

            if (NoiseSuppressor.isAvailable()) {
                m_suppressor = NoiseSuppressor.create(audiorecorder.audioSessionId)

            }
            if (AcousticEchoCanceler.isAvailable()) {
                m_canceler = AcousticEchoCanceler.create(AudioTracker.audioSessionId)

            }




            while (true.also { m_isRun = it }) {
                audiorecorder.read(buffer, 0, buffer.size)
                AudioTracker.write(buffer, 0, bufferSize)
                AudioManager.SCO_AUDIO_STATE_CONNECTED

            }
            Log.i("LOG", "loopback exit")
        } catch (e: Exception) {
            // TODO: handle exception
        }
    }


    private fun startRecord() {
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.run {
            when {
                switch2.isChecked && !isBluetoothScoOn -> {
                    startScoRecord = true
                    startBluetoothSco()
                }
                !switch2.isChecked && isBluetoothScoOn -> {
                    stopScoRecord = true
                    stopBluetoothSco()
                }
                else -> {
                    startRecording()
                }
            }
        }
    }

    private fun startRecording(){



        if(!permissionGranted){
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
            return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }



        mediaRecorder = MediaRecorder()
        dirPath = "${externalCacheDir?.absolutePath}/"

        var simpleDateFormat = SimpleDateFormat("yyyy.MM.DD_hh.mm.ss")
        var date = simpleDateFormat.format(Date())

        filename = "audio_record_$date"


        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)
            setOutputFile("$dirPath$filename.mp3")

            try {
                prepare()
            } catch (e: IOException) {
            }
            start()


            btnRecord.setImageResource(R.drawable.ic_pause)
            isRecording = true
            isPaused = false

            timer.start()


            btnDelete.isClickable = true
            btnDelete.setImageResource(R.drawable.ic_delete)

            btnList.visibility = View.GONE
            btnDone.visibility = View.VISIBLE
        }



    }
    private fun auto(){
        m_thread!!.start()
        button5.isClickable = true
        button5.visibility = View.VISIBLE




    }
    private fun button6(){
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.run {
            when {
                switch2.isChecked && !isBluetoothScoOn -> {
                    startScoRecord = true
                    startBluetoothSco()
                }
                !switch2.isChecked && isBluetoothScoOn -> {
                    stopScoRecord = true
                    stopBluetoothSco()
                }
                else -> {
        AudioTracker.stop()
        button5.isClickable = true
        button6.isClickable = false
        button6.visibility = View.GONE
        button5.visibility = View.VISIBLE

                }}}




    }
    private fun button5() {
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.run {
            when {
                switch2.isChecked && !isBluetoothScoOn -> {
                    startScoRecord = true
                    startBluetoothSco()
                }
                !switch2.isChecked && isBluetoothScoOn -> {
                    stopScoRecord = true
                    stopBluetoothSco()
                }
                else -> {
                    AudioTracker.play()
                    button6.isClickable = true
                    button6.visibility = View.VISIBLE
                    button5.visibility = View.GONE
                    button5.isClickable = false

                }

            }}


    }
    private fun stopRecorder() {


        timer.stop()

        mediaRecorder.apply {
            stop()
            release()
        }
        isPaused = false
        isRecording = false

        btnList.visibility = View.VISIBLE
        btnDone.visibility = View.GONE

        btnDelete.isClickable = false
        btnDelete.setImageResource(R.drawable.ic_delete_disabled)

        btnRecord.setImageResource(R.drawable.ic_record)

        tvTimer.text = "00:00.00"



        amplitudes = WaveformView.clear()

        Toast.makeText(this, "Recording has been deleted ", Toast.LENGTH_SHORT).show()




    }



    override fun onTimerTick(duration: String) {

        tvTimer.text = duration
        this.duration = duration.dropLast(3)
        WaveformView.addAmplitude(mediaRecorder.maxAmplitude.toFloat())


    }




    private fun switchmic(){
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.run {
            when {
                switch2.isChecked && !isBluetoothScoOn -> {
                    startScoRecord = true
                    startBluetoothSco()
                }
                !switch2.isChecked && isBluetoothScoOn -> {
                    stopScoRecord = true
                    stopBluetoothSco()
                }
                }}}



    private fun onSocStateChanged(enable: Boolean) {
        if (startScoRecord && enable) {
            startScoRecord = false

        }
        if (stopScoRecord && !enable) {
            stopScoRecord = false

        }
    }















}






