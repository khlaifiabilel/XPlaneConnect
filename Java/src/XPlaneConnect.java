package gov.nasa.xpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.AutoCloseable;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents a client that can connect to and interact with the X-Plane Connect plugin.
 *
 * @author  Jason Watkins
 * @version 0.1
 * @since   2015-03-31
 */
public class XPlaneConnect implements AutoCloseable
{
    private DatagramSocket socket;
    private InetAddress xplaneAddr;
    private int xplanePort;

    /**
     * Gets the port on which the client receives data from the plugin.
     *
     * @return The incoming port number.
     */
    public int getRecvPort() { return socket.getLocalPort(); }

    /**
     * Gets the port on which the client sends data to X-Plane.
     *
     * @return The outgoing port number.
     */
    public int getXPlanePort() { return xplanePort; }

    /**
     * Sets the port on which the client sends data to X-Plane
     *
     * @param port The new outgoing port number.
     * @throws IllegalArgumentException If {@code port} is not a valid port number.
     */
    public void setXPlanePort(int port)
    {
        if(port < 0 || port >= 0xFFFF)
        {
            throw new IllegalArgumentException("Invalid port (must be non-negative and less than 65536).");
        }
        xplanePort = port;
    }

    /**
     * Gets the hostname of the X-Plane host.
     *
     * @return The hostname of the X-Plane host.
     */
    public String getXPlaneAddr() { return xplaneAddr.getHostAddress(); }

    /**
     * Sets the hostname of the X-Plane host.
     *
     * @param host The new hostname of the X-Plane host machine.
     * @throws UnknownHostException {@code host} is not valid.
     */
    public void setXplaneAddr(String host) throws UnknownHostException
    {
        xplaneAddr = InetAddress.getByName(host);
    }

    /**
     * Initializes a new instance of the {@code XPlaneConnect} class using default ports and assuming X-Plane is running on the
     * local machine.
     *
     * @throws SocketException If this instance is unable to bind to the default receive port.
     */
    public XPlaneConnect() throws SocketException
    {
        this(100);
    }

    /**
     * Initializes a new instance of the {@code XPlaneConnect} class with the specified timeout using default ports and
     * assuming X-Plane is running on the local machine.
     *
     * @param timeout The time, in milliseconds, after which read attempts will timeout.
     * @throws SocketException If this instance is unable to bind to the default receive port.
     */
    public XPlaneConnect(int timeout) throws SocketException
    {
        this.socket = new DatagramSocket(49008);
        this.xplaneAddr = InetAddress.getLoopbackAddress();
        this.xplanePort = 49009;
        this.socket.setSoTimeout(timeout);
    }

    /**
     * Initializes a new instance of the {@code XPlaneConnect} class using the specified ports and X-Plane host.
     *
     * @param port       The port on which the X-Plane Connect plugin is sending data.
     * @param xplaneHost The network host on which X-Plane is running.
     * @param xplanePort The port on which the X-Plane Connect plugin is listening.
     * @throws java.net.SocketException      If this instance is unable to bind to the specified port.
     * @throws java.net.UnknownHostException If the specified hostname can not be resolved.
     */
    public XPlaneConnect(int port, String xplaneHost, int xplanePort)
            throws java.net.SocketException, java.net.UnknownHostException
    {
        this(port, xplaneHost, xplanePort, 100);
    }

    /**
     * Initializes a new instance of the {@code XPlaneConnect} class using the specified ports, hostname, and timeout.
     *
     * @param port       The port on which the X-Plane Connect plugin is sending data.
     * @param xplaneHost The network host on which X-Plane is running.
     * @param xplanePort The port on which the X-Plane Connect plugin is listening.
     * @param timeout    The time, in milliseconds, after which read attempts will timeout.
     * @throws java.net.SocketException      If this instance is unable to bind to the specified port.
     * @throws java.net.UnknownHostException If the specified hostname can not be resolved.
     */
    public XPlaneConnect(int port, String xplaneHost, int xplanePort, int timeout)
            throws java.net.SocketException, java.net.UnknownHostException
    {
        this.socket = new DatagramSocket(port);
        this.xplaneAddr = InetAddress.getByName(xplaneHost);
        this.xplanePort = xplanePort;
        this.socket.setSoTimeout(timeout);
    }

    /**
     * Closes the underlying socket.
     */
    public void close()
    {
        if(socket != null)
        {
            socket.close();
            socket = null;
        }
    }

    /**
     * Read data from the X-Plane plugin. This method will read whatever data is available and return it.
     *
     * @return The data send from X-Plane.
     * @throws IOException If the read operation fails
     */
    private byte[] readUDP() throws IOException //TODO: Store data in a class level buffer to account for partial messages
    {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try
        {
            socket.receive(packet);
            return Arrays.copyOf(buffer, buffer[4]);
        }
        catch (SocketTimeoutException ex)
        {
            return new byte[0];
        }
    }

    /**
     * Send the given data to the X-Plane plugin. This method automatically sets the length byte before sending,
     * overwriting any value previously stored in @{code buffer[4]}.
     *
     * @param buffer The data to send.
     * @throws IOException If the send operation fails.
     */
    private void sendUDP(byte[] buffer) throws IOException
    {
        if(buffer.length < 5 || buffer.length > 255)
        {
            throw new IllegalArgumentException("buffer must be between 5 and 255 bytes long.");
        }
        buffer[4] = (byte)buffer.length;

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, xplaneAddr, xplanePort);
        socket.send(packet);
    }

    /**
     * Pauses or unpauses X-Plane.
     *
     * @param pause {@code true} to pause the simulator; {@code false} to un-pause.
     * @throws IOException If the command cannot be sent.
     */
    public void pauseSim(boolean pause) throws IOException
    {
        //            S     I     M     U     LEN   VAL
        byte[] msg = {0x53, 0x49, 0x4D, 0x55, 0x00, 0x00};
        msg[5] = (byte)(pause ? 0x01 : 0x00);
        sendUDP(msg);
    }

    /**
     * Requests a single dref value from X-Plane.
     *
     * @param dref The name of the dref requested.
     * @return     A byte array representing data dependent on the dref requested.
     * @throws IOException If either the request or the response fails.
     */
    public float[] requestDREF(String dref) throws IOException
    {
        return requestDREFs(new String[]{dref})[0];
    }

    /**
     * Requests several dref values from X-Plane.
     *
     * @param drefs An array of dref names to request.
     * @return      A multidimensional array representing the data for each requested dref.
     * @throws IOException If either the request or the response fails.
     */
    public float[][] requestDREFs(String[] drefs) throws IOException
    {
        //Preconditions
        if(drefs == null || drefs.length == 0)
        {
            throw new IllegalArgumentException("drefs must be a valid array with at least one dref.");
        }
        if(drefs.length > 255)
        {
            throw new IllegalArgumentException("Can not request more than 255 DREFs at once.");
        }

        //Convert drefs to bytes.
        byte[][] drefBytes = new byte[drefs.length][];
        for(int i = 0; i < drefs.length; ++i)
        {
            drefBytes[i] = drefs[i].getBytes(StandardCharsets.UTF_8);
            if(drefBytes[i].length == 0)
            {
                throw new IllegalArgumentException("DREF " + i + " is an empty string!");
            }
            if(drefBytes[i].length > 255)
            {
                throw new IllegalArgumentException("DREF " + i + " is too long (must be less than 255 bytes in UTF-8). Are you sure this is a valid DREF?");
            }
        }

        //Build and send message
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("GETD".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write(drefs.length);
        for(byte[] dref : drefBytes)
        {
            os.write(dref.length);
            os.write(dref, 0, dref.length);
        }
        sendUDP(os.toByteArray());

        //Read response
        for(int i = 0; i < 40; ++i)
        {
            byte[] data = readUDP();
            if(data.length == 0)
            {
                continue;
            }
            if(data.length < 6)
            {
                throw new Error("Response too short"); //TODO: Make custom error type
            }
            if(data[5] != drefs.length)
            {
                throw new Error("Unexpected response length"); //TODO: Make custom error type
            }
            float[][] result = new float[drefs.length][];
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            int cur = 6;
            for(int j = 0; j < result.length; ++j)
            {
                result[j] = new float[data[cur++]];
                for(int k = 0; k < result[j].length; ++k) //TODO: There must be a better way to do this
                {
                    result[j][k] = bb.getFloat(cur);
                    cur += 4;
                }
            }
            return result;
        }
        throw new IOException("No response received.");
    }

    public void sendDREF(String dref, float value) throws IOException
    {
        sendDREF(dref, new float[] {value});
    }

    /**
     * Sends a command to X-Plane that sets the given DREF.
     *
     * @param dref  The name of the DREF to set.
     * @param value An array of floating point values whose structure depends on the dref specified.
     * @throws IOException If the command cannot be sent.
     */
    public void sendDREF(String dref, float[] value) throws IOException
    {
        //Preconditions
        if(dref == null)
        {
            throw new IllegalArgumentException("dref must be a valid string.");
        }
        if(value == null || value.length == 0)
        {
            throw new IllegalArgumentException("value must be non-null and should contain at least one value.");
        }

        //Convert drefs to bytes.
        byte[] drefBytes = dref.getBytes(StandardCharsets.UTF_8);
        if(drefBytes.length == 0)
        {
            throw new IllegalArgumentException("DREF is an empty string!");
        }
        if(drefBytes.length > 255)
        {
            throw new IllegalArgumentException("dref must be less than 255 bytes in UTF-8. Are you sure this is a valid dref?");
        }

        ByteBuffer bb = ByteBuffer.allocate(4 * value.length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for(int i = 0; i < value.length; ++i)
        {
            bb.putFloat(i * 4, value[i]);
        }

        //Build and send message
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("DREF".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write(drefBytes.length);
        os.write(drefBytes, 0, drefBytes.length);
        os.write(value.length);
        os.write(bb.array());
        sendUDP(os.toByteArray());
    }

    /**
     * Sends command to X-Plane setting control surfaces on the player aircraft.
     *
     * @param ctrl <p>An array containing zero to six values representing control surface data as follows:</p>
     *             <ol>
     *                 <li>Latitudinal Stick [-1,1]</li>
     *                 <li>Longitudinal Stick [-1,1]</li>
     *                 <li>Rudder Pedals [-1, 1]</li>
     *                 <li>Throttle [-1, 1]</li>
     *                 <li>Gear (0=up, 1=down)</li>
     *                 <li>Flaps [0, 1]</li>
     *             </ol>
     *             <p>
     *                 If @{code ctrl} is less than 6 elements long, The missing elements will not be changed. To
     *                 change values in the middle of the array without affecting the preceding values, set the
     *                 preceding values to -998.
     *             </p>
     * @throws IOException If the command cannot be sent.
     */
    public void sendCTRL(float[] ctrl) throws IOException
    {
        sendCTRL(ctrl, 0);
    }

    /**
     * Sends command to X-Plane setting control surfaces on the specified aircraft.
     *
     * @param ctrl     <p>An array containing zero to six values representing control surface data as follows:</p>
     *                 <ol>
     *                     <li>Latitudinal Stick [-1,1]</li>
     *                     <li>Longitudinal Stick [-1,1]</li>
     *                     <li>Rudder Pedals [-1, 1]</li>
     *                     <li>Throttle [-1, 1]</li>
     *                     <li>Gear (0=up, 1=down)</li>
     *                     <li>Flaps [0, 1]</li>
     *                 </ol>
     *                 <p>
     *                     If @{code ctrl} is less than 6 elements long, The missing elements will not be changed. To
     *                     change values in the middle of the array without affecting the preceding values, set the
     *                     preceding values to -998.
     *                 </p>
     * @param aircraft The aircraft to set. 0 for the player's aircraft.
     * @throws IOException If the command cannot be sent.
     */
    private void sendCTRL(float[] ctrl, int aircraft) throws IOException
    {
        //Preconditions
        if(ctrl == null)
        {
            throw new IllegalArgumentException("ctrl must no be null.");
        }
        if(ctrl.length > 6)
        {
            throw new IllegalArgumentException("ctrl must have 6 or fewer elements.");
        }
        if(aircraft < 0)
        {
            throw new IllegalArgumentException("aircraft must be non-negative.");
        }
        if(aircraft != 0) //TODO: Implement support for non-player aircraft on plugin side.
        {
            throw new Error("Non-player aircraft not supported yet.");
        }

        //Pad command values and convert to bytes
        int i;
        int cur = 0;
        ByteBuffer bb = ByteBuffer.allocate(22);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for(i = 0; i < ctrl.length; ++i)
        {
            if(i == 4)
            {
                bb.put(cur, (byte) ctrl[i]);
                cur += 1;
            }
            else
            {
                bb.putFloat(cur, ctrl[i]);
                cur += 4;
            }
        }
        for(; i < 6; ++i)
        {
            if(i == 4)
            {
                bb.put(cur, (byte) 0);
                cur += 1;
            }
            else
                {
                bb.putFloat(cur, -998);
                cur += 4;
            }
        }

        //Build and send message
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("CTRL".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write(bb.array());
        sendUDP(os.toByteArray());
    }

    /**
     * Sets the position of the player aircraft.
     *
     * @param posi     <p>An array containing position elements as follows:</p>
     *                 <ol>
     *                     <li>Latitiude (deg)</li>
     *                     <li>Longitude (deg)</li>
     *                     <li>Altitude (m above MSL)</li>
     *                     <li>Roll (deg)</li>
     *                     <li>Pitch (deg)</li>
     *                     <li>True Heading (deg)</li>
     *                     <li>Gear (0=up, 1=down)</li>
     *                 </ol>
     *                 <p>
     *                     If @{code ctrl} is less than 6 elements long, The missing elements will not be changed. To
     *                     change values in the middle of the array without affecting the preceding values, set the
     *                     preceding values to -998.
     *                 </p>
     * @throws IOException If the command can not be sent.
     */
    public void sendPOSI(float[] posi) throws IOException
    {
        sendPOSI(posi, 0);
    }

    /**
     * Sets the position of the specified aircraft.
     *
     * @param posi     <p>An array containing position elements as follows:</p>
     *                 <ol>
     *                     <li>Latitiude (deg)</li>
     *                     <li>Longitude (deg)</li>
     *                     <li>Altitude (m above MSL)</li>
     *                     <li>Roll (deg)</li>
     *                     <li>Pitch (deg)</li>
     *                     <li>True Heading (deg)</li>
     *                     <li>Gear (0=up, 1=down)</li>
     *                 </ol>
     *                 <p>
     *                     If @{code ctrl} is less than 6 elements long, The missing elements will not be changed. To
     *                     change values in the middle of the array without affecting the preceding values, set the
     *                     preceding values to -998.
     *                 </p>
     * @param aircraft The aircraft to set. 0 for the player aircraft.
     * @throws IOException If the command can not be sent.
     */
    public void sendPOSI(float[] posi, int aircraft) throws IOException
    {
        //Preconditions
        if(posi == null)
        {
            throw new IllegalArgumentException("posi must no be null.");
        }
        if(posi.length > 7)
        {
            throw new IllegalArgumentException("posi must have 7 or fewer elements.");
        }
        if(aircraft < 0 || aircraft > 255)
        {
            throw new IllegalArgumentException("aircraft must be between 0 and 255.");
        }

        //Pad command values and convert to bytes
        int i;
        ByteBuffer bb = ByteBuffer.allocate(28);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for(i = 0; i < posi.length; ++i)
        {
            bb.putFloat(i * 4, posi[i]);
        }
        for(; i < 7; ++i)
        {
            bb.putFloat(i * 4, -998);
        }

        //Build and send message
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("POSI".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write(aircraft);
        os.write(bb.array());
        sendUDP(os.toByteArray());
    }

    public void sendDATA(float[][] data) throws IOException
    {
        //Preconditions
        if(data == null || data.length == 0)
        {
            throw new IllegalArgumentException("data must be a non-null, non-empty array.");
        }

        //Convert data to bytes
        ByteBuffer bb = ByteBuffer.allocate(4 * 9 * data.length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for(int i = 0; i < data.length; ++i)
        {
            int rowStart = 9 * 4 * i;
            float[] row = data[i];
            if(row.length != 9)
            {
                throw new IllegalArgumentException("Rows must contain exactly 9 items. (Row " + i + ")");
            }

            bb.putInt(rowStart, (int) row[0]);
            for(int j = 1; j < row.length; ++j)
            {
                bb.putFloat(rowStart + 4 * j, row[j]);
            }
        }

        //Build and send message
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("DATA".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write(bb.array());
        sendUDP(os.toByteArray());
    }

    /**
     *
     *
     * @param recvPort
     * @throws IOException
     */
    public void setCONN(int recvPort) throws IOException
    {
        if(recvPort < 0 || recvPort >= 0xFFFF)
        {
            throw new IllegalArgumentException("Invalid port (must be non-negative and less than 65536).");
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write("CONN".getBytes(StandardCharsets.UTF_8));
        os.write(0xFF); //Placeholder for message length
        os.write((byte)recvPort);
        os.write((byte)(recvPort >> 8));
        sendUDP(os.toByteArray());

        socket.close();
        socket = new DatagramSocket(recvPort);
    }
}
