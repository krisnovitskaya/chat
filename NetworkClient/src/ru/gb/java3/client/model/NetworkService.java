package ru.gb.java3.client.model;

import ru.gb.java3.client.controller.AuthEvent;
import ru.gb.java3.client.controller.ClientController;
import ru.gb.java3.clientserver.Command;
import ru.gb.java3.clientserver.command.*;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.function.Consumer;

public class NetworkService {
    private final String serverIP;
    private final int port;
    private Socket socket;
    private ObjectInputStream in;
    private ObjectOutputStream out;

    private ClientController controller;

    private Consumer<String> messageHandler;
    private AuthEvent successfulAuthEvent;
    private String nick;

    private int id;


    public NetworkService(String serverIP, int port){
        this.serverIP = serverIP;
        this.port = port;
    }


    public void connect(ClientController controller) throws IOException {
        this.controller =controller;
        socket = new Socket(serverIP, port);
        in = new ObjectInputStream(socket.getInputStream());
        out = new ObjectOutputStream(socket.getOutputStream());
        runReadingThread();
    }

    private void runReadingThread() {
        new Thread(() -> {
            while(true){
                try {
                    Command command = (Command) in.readObject();
                    switch (command.getType()){
                        case AUTH:{
                            AuthCommand commandData = (AuthCommand) command.getData();
                            nick = commandData.getUsername();
                            id = commandData.getID();
                            successfulAuthEvent.authIsSuccessful(nick, id);
                            break;
                        }
                        case MESSAGE:{
                            MessageCommand commandData = (MessageCommand) command.getData();
                            if(messageHandler != null){
                                String message = commandData.getMessage();
                                String username = commandData.getUsername();
                                if(username != null){
                                    message = username + ": " + message;
                                }
                                messageHandler.accept(message);
                            }
                            break;
                        }
                        case AUTH_ERROR:
                        case ERROR:{
                            ErrorCommand commandData = (ErrorCommand) command.getData();
                            controller.showErrorMessage(commandData.getErrorMessage());
                            break;
                        }
                        case UPDATE_USER_LIST:{
                            UpdateUsersListCommand commandData = (UpdateUsersListCommand) command.getData();
                            List<String> users = commandData.getUsers();
                            controller.updateUsersList(users);
                            break;
                        }
                        case CHANGE_NICK:{
                            ChangeNickCommand commandData = (ChangeNickCommand) command.getData();
                            nick = commandData.getUsername();
                            controller.setNewNick(nick);
                            controller.changeNickDialogClose();
                            break;
                        }
                        default:
                            System.err.println("unknown type of command: " + command.getType());
                    }
                } catch (IOException e) {
                    System.out.println("Поток чтения был прерван!");
                    return;
                } catch (ClassNotFoundException e){
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void setSuccessfulAuthEvent(AuthEvent successfulAuthEvent) {
        this.successfulAuthEvent = successfulAuthEvent;
    }



    public void sendCommand(Command command) throws IOException{
        out.writeObject(command);
    }

    public void setMessageHandler(Consumer<String> messageHandler){
        this.messageHandler = messageHandler;
    }

    public void close(){
        try{
            socket.close();
        }catch (IOException e){
            e. printStackTrace();
        }
    }
}
