/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Christopher Robert Philabaum
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package name.cphilabaum.feedback;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;

public class FeedbackServer extends WebSocketServer
{
    private Survey survey;
    final public int MAX_ENTRIES;

    /**
     * Starts the server selectorthread that binds to the currently set port number and
     * listeners for WebSocket connection requests. Creates a fixed thread pool with the size {@link WebSocketServer#DECODERS}<br>
     * May only be called once.
     * <p>
     * Alternatively you can call {@link WebSocketServer#run()} directly.
     *
     * @throws IllegalStateException
     */
    @Override
    public void start()
    {
        SurveyFile file = new SurveyFile();

        try
        {
            this.survey = file.read();
        }
        catch (IOException e)
        {
//            e.printStackTrace();
            System.err.println(e.getMessage());
            this.survey = new Survey();
        }
        catch (MalformedFileException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        super.start();
    }

    @Override
    public void stop() throws IOException, InterruptedException
    {
        super.stop();
        SurveyFile file = new SurveyFile();
        file.write(this.survey);
    }

    /**
     * Creates a WebSocketServer that will attempt to bind/listen on the given <var>address</var>.
     *
     * @param address
     * @see #WebSocketServer(InetSocketAddress, int, List, Collection) more details here
     */
    public FeedbackServer(InetSocketAddress address, int MAX_ENTRIES)
    {
        super(address);

        this.survey = new Survey();
        this.MAX_ENTRIES = MAX_ENTRIES;
    }

    /**
     * Called after an opening handshake has been performed and the given websocket is ready to be written on.
     *
     * @param conn
     * @param handshake
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake)
    {
        for(int i = 0; i < survey.getEntries().size(); i++)
        {
            conn.send(String.format("ADD \"%s\" %d %d", survey.getEntry(i).getName(), i,
                    survey.getEntry(i).getVotes()));
        }

        conn.send("HELP");
        conn.send("ADD [name] - Adds a survey entry.");
        conn.send("UP [name] - Ups an entry's vote by one.");
        conn.send("DOWN [name] - Downs an entry's vote by one.");
        conn.send("UNHELP");
    }

    /**
     * Called after the websocket connection has been closed.
     *
     * @param conn
     * @param code   The codes can be looked up here: {@link CloseFrame}
     * @param reason Additional information string
     * @param remote
     **/
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote)
    {

    }

    /**
     * Callback for string messages received from the remote host
     *
     * @param conn
     * @param message
     * @see #onMessage(WebSocket, ByteBuffer)
     */
    @Override
    public void onMessage(WebSocket conn, String message)
    {
        try
        {
            String[] components = parseMessage(message);
            String command = components[0];
            String arg = components[1];
            switch(command)
            {
                case("ADD"):
                    if(!arg.startsWith("\"") || !arg.endsWith("\""))
                    {
                        throw new IllegalArgumentException("Argument does not begin or end with \".");
                    }
                    if(MAX_ENTRIES == survey.getEntries().size())
                    {
                        throw new EntryLimitExceededException();
                    }
                    String name = arg.substring(1,arg.length()-1);
                    updateAdd(new Entry(name));
                    break;
                case("UP"):
                    updateUp(Integer.parseInt(arg));
                    break;
                case("DOWN"):
                    updateDown(Integer.parseInt(arg));
                    break;
                default:
                    conn.send("Invalid command.");
                    break;
            }
        }
        catch(EntryAlreadyExistsException e)
        {
            conn.send("Entry already exists.");
        }
        catch(NumberFormatException e)
        {
            conn.send("Index not valid.");
        }
        catch(IndexOutOfBoundsException e)
        {
            conn.send("Index does not exist.");
        }
        catch(InvalidEntryNameException | IllegalArgumentException | EntryLimitExceededException e)
        {
            conn.send(e.getMessage());
        }
    }

    /**
     * Called when errors occurs. If an error causes the websocket connection to fail {@link #onClose(WebSocket, int, String, boolean)} will be called additionally.<br>
     * This method will be called primarily because of IO or protocol errors.<br>
     * If the given exception is an RuntimeException that probably means that you encountered a bug.<br>
     *
     * @param conn Can be null if there error does not belong to one specific websocket. For example if the servers port could not be bound.
     * @param ex
     **/
    @Override
    public void onError(WebSocket conn, Exception ex)
    {
        ex.printStackTrace();
    }

    private void updateUp(int index) throws IndexOutOfBoundsException
    {
        survey.getEntry(index).upVotes();
        sendToAll("UP " + Integer.toString(index));
    }

    private void updateDown(int index) throws IndexOutOfBoundsException
    {
        survey.getEntry(index).downVotes();
        sendToAll("DOWN " + Integer.toString(index));
    }

    private void updateAdd(Entry entry) throws EntryAlreadyExistsException, InvalidEntryNameException
    {
        if(survey.contains(entry.getName()))
        {
            throw new EntryAlreadyExistsException();
        }

        survey.addEntry(entry);
        int index = survey.getIndex(entry.getName());
        sendToAll(String.format("ADD \"%s\" %d %d", entry.getName(), index, entry.getVotes()));
    }

    protected void updateRemove(int index) throws IndexOutOfBoundsException
    {
        survey.removeEntry(index);
        sendToAll(String.format("REMOVE %d", index));
    }

    private void sendToAll(String s)
    {
        for(WebSocket conn : connections())
        {
            conn.send(s);
        }
    }

    protected Survey getSurvey()
    {
        return survey;
    }

    private String[] parseMessage(String message)
    {
        String[] components = message.split(" ",2);
        if(components.length < 2)
        {
            throw new IllegalArgumentException("Commands need at least 2 arguments.");
        }

        return components;
    }
}
