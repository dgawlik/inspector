package com.github.crud_app;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

@RestController
public class BookController {

    @Autowired
    private BookRepository bookRepository;

    @GetMapping("/books")
    public List<Book> getBooks() {
        return bookRepository.findAll();
    }

    @PostMapping("/books")
    public String addBook(@RequestBody Book book) {
        bookRepository.save(book);
        return "Book added successfully";
    }

    @DeleteMapping("/books")
    public String deleteBook(@RequestParam Long id) {
        bookRepository.deleteById(id);
        return "Book deleted successfully";
    }

    @PutMapping("/books")
    public String updateBook(@RequestBody Book book) {
        bookRepository.save(book);
        return "Book updated successfully";
    }
}
