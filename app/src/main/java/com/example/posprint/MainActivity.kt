package com.example.posprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.connection.usb.UsbConnection
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // --- Invoice Variables ---
    private var printInvoiceId = ""
    private var printCustomerName = ""
    private var printTotal = ""
    private var printItemsString = ""
    private var printDate = ""

    // --- UI Elements ---
    private lateinit var txtStatus: TextView

    // --- USB Permission Helper ---
    private val ACTION_USB_PERMISSION = "com.example.displaydetails.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtPrinterStatus)

        // Manual Settings Button
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showPrinterSelectionDialog()
        }

        checkBluetoothPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @SuppressLint("SetTextI18n")
    private fun handleIntent(intent: Intent?) {
        val uriData: Uri? = intent?.data
        if (uriData != null) {
            // 1. Get Data
            printInvoiceId = uriData.getQueryParameter("id") ?: "000"
            printCustomerName = uriData.getQueryParameter("cus") ?: "Guest"
            printTotal = uriData.getQueryParameter("tot") ?: "0.00"
            val itemsJsonString = uriData.getQueryParameter("items")

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            printDate = sdf.format(Date())

            // 2. Update UI
            findViewById<TextView>(R.id.txtInvoiceId).text = "Invoice: #$printInvoiceId"
            findViewById<TextView>(R.id.txtCustomer).text = "Customer: $printCustomerName"
            findViewById<TextView>(R.id.txtTotal).text = "$printTotal"

            // 3. Process Items
            val container = findViewById<LinearLayout>(R.id.itemsContainer)
            container.removeAllViews()
            printItemsString = ""

            if (!itemsJsonString.isNullOrEmpty()) {
                try {
                    val jsonArray = JSONArray(itemsJsonString)
                    for (i in 0 until jsonArray.length()) {
                        val itemObj = jsonArray.getJSONObject(i)
                        val name = itemObj.optString("name", "Item")
                        val qty = itemObj.optString("qty", "1")
                        val amount = itemObj.optString("amt", "0.00")

                        addScreenRow(container, name, qty, amount)
                        printItemsString += "[L]${name} x${qty}[R]${amount}\n"
                    }
                } catch (e: Exception) { }
            }

            // 4. Start Printing Logic
            findAndPrint()
        }
    }

    // ============================================================================
    // HYBRID PRINTING LOGIC (USB + BLUETOOTH) WITH NAME DISPLAY
    // ============================================================================

    @SuppressLint("MissingPermission")
    private fun findAndPrint() {
        txtStatus.text = "Searching..."
        txtStatus.setTextColor(Color.parseColor("#FBC02D")) // Yellow

        Thread {
            try {
                var connection: DeviceConnection? = null
                var printerNameForUI = "Unknown"

                // ---------------------------------------------------------
                // 1. CHECK USB FIRST (Priority)
                // ---------------------------------------------------------
                val usbConnection = UsbPrintersConnections.selectFirstConnected(this)

                if (usbConnection != null) {
                    connection = usbConnection

                    // --- GET USB NAME ---
                    val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        usbConnection.device.manufacturerName ?: ""
                    } else { "" }

                    val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        usbConnection.device.productName ?: "USB Device"
                    } else { "USB Device" }

                    printerNameForUI = "USB: $manufacturer $product".trim()
                }
                // ---------------------------------------------------------
                // 2. CHECK BLUETOOTH SECOND (Fallback)
                // ---------------------------------------------------------
                else {
                    val bluetoothConnections = BluetoothPrintersConnections()
                    val pairedDevices = bluetoothConnections.list

                    if (!pairedDevices.isNullOrEmpty()) {
                        // A. Try Saved Printer
                        val savedAddress = getSavedPrinterAddress()
                        if (savedAddress != null) {
                            connection = pairedDevices.find { it.device.address == savedAddress }
                        }

                        // B. Smart Search (Keywords)
                        if (connection == null) {
                            connection = pairedDevices.find {
                                val name = it.device.name.uppercase()
                                name.contains("POS") || name.contains("XP") || name.contains("PRINT") || name.contains("EPSON")
                            }
                            if (connection != null) savePrinterAddress((connection as BluetoothConnection).device.address)
                        }

                        // C. First Available (Any Printer)
                        if (connection == null) {
                            connection = pairedDevices[0]
                            savePrinterAddress(connection!!.device.address)
                        }

                        // --- GET BLUETOOTH NAME ---
                        printerNameForUI = "BT: " + (connection as BluetoothConnection).device.name
                    }
                }

                // ---------------------------------------------------------
                // 3. EXECUTE PRINT
                // ---------------------------------------------------------
                if (connection != null) {
                    // Update Status on Screen with PRINTER NAME
                    runOnUiThread {
                        txtStatus.text = printerNameForUI
                        txtStatus.setTextColor(Color.parseColor("#388E3C")) // Green
                    }

                    // CHECK USB PERMISSION
                    if (connection is UsbConnection) {
                        val usbManager = this.getSystemService(Context.USB_SERVICE) as UsbManager
                        if (connection.device != null && !usbManager.hasPermission(connection.device)) {
                            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                            usbManager.requestPermission(connection.device, permissionIntent)
                            runOnUiThread {
                                Toast.makeText(this, "Allow Permission for $printerNameForUI", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                    }

                    val printer = EscPosPrinter(connection, 203, 48f, 32)

                    val receiptText = """
                        [C]<b><font size='big'>Village Bakery POS</font></b
                        [C]--------------------------------
                        [L]Date: $printDate
                        [L]Invoice: <b>$printInvoiceId</b>
                        [L]Customer: $printCustomerName
                        [C]--------------------------------
                        [L]<b>ITEM</b>[R]<b>AMOUNT</b>
                        [C]--------------------------------
                        $printItemsString
                        [C]--------------------------------
                        [L]<b>TOTAL:</b>[R]<b><font size='big'>$printTotal</font></b>
                        [C]--------------------------------
                        [C]Thank you for your business!
                        [L]
                        [L]
                        [L]
                    """.trimIndent()

                    printer.printFormattedText(receiptText)

                    // Feed Paper
                    try { connection.write(byteArrayOf(27, 100, 4)) } catch (e: Exception) {}

                    Thread.sleep(500)
                    if (connection is BluetoothConnection) {
                        printer.disconnectPrinter()
                    }

                    // Success Toast with PRINTER NAME
                    runOnUiThread {
                        Toast.makeText(this, "Printed: $printerNameForUI", Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true) // Minimize app
                    }
                } else {
                    runOnUiThread {
                        txtStatus.text = "No Printer Found"
                        txtStatus.setTextColor(Color.RED)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    txtStatus.text = "Error: ${e.message}"
                }
            }
        }.start()
    }

    // --- Helpers ---

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPrinterSelectionDialog() {
        val bluetoothConnections = BluetoothPrintersConnections()
        val list = bluetoothConnections.list
        if (list.isNullOrEmpty()) {
            Toast.makeText(this, "No Bluetooth devices!", Toast.LENGTH_LONG).show()
            return
        }
        val namesArray = list.map { it.device.name + "\n" + it.device.address }.toTypedArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Bluetooth Printer")
        builder.setItems(namesArray) { _, which ->
            savePrinterAddress(list[which].device.address)
            findAndPrint()
        }
        builder.show()
    }

    private fun savePrinterAddress(address: String) {
        getPreferences(Context.MODE_PRIVATE).edit().putString("saved_printer_mac", address).apply()
    }

    private fun getSavedPrinterAddress(): String? {
        return getPreferences(Context.MODE_PRIVATE).getString("saved_printer_mac", null)
    }

    private fun addScreenRow(container: LinearLayout, name: String, qty: String, amount: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 15, 0, 15)

        val txtName = TextView(this)
        txtName.text = name
        txtName.layoutParams = LinearLayout.LayoutParams(0, -2, 2f)
        txtName.setTextColor(Color.BLACK)

        val txtQty = TextView(this)
        txtQty.text = qty
        txtQty.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        txtQty.gravity = Gravity.CENTER
        txtQty.setTextColor(Color.BLACK)

        val txtAmt = TextView(this)
        txtAmt.text = amount
        txtAmt.layoutParams = LinearLayout.LayoutParams(0, -2, 1.5f)
        txtAmt.gravity = Gravity.END
        txtAmt.setTextColor(Color.BLACK)

        row.addView(txtName)
        row.addView(txtQty)
        row.addView(txtAmt)
        container.addView(row)
    }
}