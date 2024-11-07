package com.github.crud_app;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BookController {

    @Autowired
    private BookService bookService;

    @GetMapping("/books")
    public List<Book> getBooks() {
        return bookService.getBooks();
    }

    @PostMapping("/books")
    public String addBook(@RequestBody Book book) {
        bookService.addBook(book);
        return "Book added successfully";
    }

    @DeleteMapping("/books")
    public String deleteBook(@RequestParam Long id) {
        bookService.deleteBook(id);
        return "Book deleted successfully";
    }

    @PutMapping("/books")
    public String updateBook(@RequestBody Book book) {
        bookService.updateBook(book);
        return "Book updated successfully";
    }
}
